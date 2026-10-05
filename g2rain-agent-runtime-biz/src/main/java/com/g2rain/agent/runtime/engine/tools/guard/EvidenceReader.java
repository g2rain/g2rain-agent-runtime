package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.graph.EdgeType;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.ExtractSpec;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.hydrate.ValueRef;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 按 extract 的 key 与 path 精确取证。
 *
 * <p>报文按需加载；可读本节点与委派前置节点的成功证据，不扫描整图。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
@Component
public class EvidenceReader {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    private final RuntimeStateFacade stateFacade;

    /**
     * 创建证据读取器。
     *
     * @param stateFacade 任务记忆读写门面，用于按需加载证据报文
     */
    public EvidenceReader(RuntimeStateFacade stateFacade) {
        this.stateFacade = stateFacade;
    }

    /**
     * 按已解析的 {@link ValueRef} 从成功证据中取值。
     *
     * <p>袋必须为 EVIDENCE。path 第一段是 extract key，其后为相对路径。
     * 只读本节点与委派前置节点的成功证据，新的优先。</p>
     *
     * @param ctx 当前工具执行上下文
     * @param ref 证据取值引用
     * @return 命中值与 EVIDENCE 来源；取不到为空
     */
    public Optional<Hit> resolve(ToolExecutionContext ctx, ValueRef ref) {
        if (ref.bag() != ValueRef.Bag.EVIDENCE || Strings.isBlank(ref.path())) {
            return Optional.empty();
        }

        String rest = ref.path();
        int dot = rest.indexOf('.');
        String key = dot < 0 ? rest : rest.substring(0, dot);
        String rel = dot < 0 ? "" : rest.substring(dot + 1);
        if (Strings.isBlank(key)) {
            return Optional.empty();
        }

        for (OwnedEvidence owned : successfulEvidenceNewestFirst(ctx)) {
            ExtractSpec extract = extractOf(ctx, owned, key);
            if (Objects.isNull(extract) || Strings.isBlank(extract.getPath())) {
                continue;
            }

            EvidenceHead head = owned.head();
            JsonNode payload = stateFacade.payload(ctx.getFrame().turn(), ctx.getTaskId(), head.evidenceId());
            if (payload.isNull() || payload.isMissingNode()) {
                continue;
            }

            JsonNode subtree = JSON.lookupNode(payload, extract.getPath().trim());
            if (!JSON.hasValue(subtree)) {
                continue;
            }

            JsonNode value = Strings.isBlank(rel) ? subtree : JSON.lookupNode(subtree, rel);
            if (!JSON.hasValue(value)) {
                continue;
            }

            String fullPath = Strings.isBlank(rel) ? extract.getPath().trim() : extract.getPath().trim() + "." + rel;
            return Optional.of(new Hit(value, new Source(SourceType.EVIDENCE, head.evidenceId(), fullPath)));
        }

        return Optional.empty();
    }

    /**
     * 参数值是否与某条成功证据在 extract 路径上精确相等。
     *
     * @param ctx    当前工具执行上下文
     * @param argVal 待比对的入参值
     * @return 能找到相等节点为 {@code true}
     */
    public boolean matchesExact(ToolExecutionContext ctx, JsonNode argVal) {
        return findMatchingEvidenceSource(ctx, argVal).isPresent();
    }

    /**
     * 在成功证据的 extract 子树中找与 argVal 精确相等的节点，返回 EVIDENCE 来源。
     *
     * <p>无 extracts 不认（不扫整报文）；有 extracts 时只在 {@code path} 子树内迭代遍历（显式栈，非递归）。</p>
     *
     * @param ctx    当前工具执行上下文
     * @param argVal 待比对的入参值
     * @return 相等节点对应的证据来源；找不到为空
     */
    public Optional<Source> findMatchingEvidenceSource(ToolExecutionContext ctx, JsonNode argVal) {
        if (!JSON.hasValue(argVal)) {
            return Optional.empty();
        }

        for (OwnedEvidence owned : successfulEvidenceNewestFirst(ctx)) {
            ToolSpec tool = toolOf(ctx, owned.ownerNodeId(), owned.head().toolName());
            if (Objects.isNull(tool) || Collections.isEmpty(tool.getExtracts())) {
                continue;
            }

            EvidenceHead head = owned.head();
            JsonNode payload = stateFacade.payload(ctx.getFrame().turn(), ctx.getTaskId(), head.evidenceId());
            if (payload.isNull() || payload.isMissingNode()) {
                continue;
            }

            for (ExtractSpec extract : tool.getExtracts()) {
                if (Strings.isBlank(extract.getPath())) {
                    continue;
                }

                String base = extract.getPath().trim();
                JsonNode subtree = JSON.lookupNode(payload, base);
                if (!JSON.hasValue(subtree)) {
                    continue;
                }

                Optional<String> rel = findEqualPathIterative(subtree, argVal);
                if (rel.isPresent()) {
                    return Optional.of(new Source(SourceType.EVIDENCE, head.evidenceId(), joinExtractPath(base, rel.get())));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 在子树内按 {@link JsonCodec#nodesEqual} 找首个相等节点的相对路径；显式栈迭代，避免递归爆栈。
     *
     * @param root   搜索根
     * @param target 期望值
     * @return 相对路径；没有相等节点为空
     */
    private static Optional<String> findEqualPathIterative(JsonNode root, JsonNode target) {
        Deque<PathFrame> stack = new ArrayDeque<>();
        stack.push(new PathFrame(root, ""));
        while (!stack.isEmpty()) {
            PathFrame frame = stack.pop();
            if (JSON.nodesEqual(frame.node(), target)) {
                return Optional.of(frame.path());
            }
            JsonNode node = frame.node();
            if (node.isObject()) {
                for (var field : node.properties()) {
                    String childPath = Strings.isBlank(frame.path())
                        ? field.getKey()
                        : frame.path() + "." + field.getKey();
                    stack.push(new PathFrame(field.getValue(), childPath));
                }
            } else if (node.isArray()) {
                for (int i = node.size() - 1; i >= 0; i--) {
                    String childPath = Strings.isBlank(frame.path())
                        ? "[" + i + "]"
                        : frame.path() + "[" + i + "]";
                    stack.push(new PathFrame(node.get(i), childPath));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 拼接抽取基路径与相对路径。
     *
     * @param base 基路径
     * @param rel  相对路径
     * @return 完整路径
     */
    private static String joinExtractPath(String base, String rel) {
        if (Strings.isBlank(rel)) {
            return base;
        }

        return rel.charAt(0) == '[' ? base + rel : base + "." + rel;
    }

    /**
     * 迭代搜索栈帧。
     *
     * @param node 当前节点
     * @param path 相对路径
     */
    private record PathFrame(JsonNode node, String path) {

    }

    /**
     * 按 evidenceId + path 取值并与期望值相等。
     *
     * @param ctx        当前工具执行上下文
     * @param evidenceId 证据标识
     * @param path       报文体路径；空白表示整份报文
     * @param expected   期望值
     * @return 路径上有值且与期望精确相等
     */
    public boolean matches(ToolExecutionContext ctx, String evidenceId, @Nullable String path, JsonNode expected) {
        if (Strings.isBlank(evidenceId) || !JSON.hasValue(expected)) {
            return false;
        }

        JsonNode payload = stateFacade.payload(ctx.getFrame().turn(), ctx.getTaskId(), evidenceId);
        if (payload.isNull() || payload.isMissingNode()) {
            return false;
        }

        JsonNode actual = Strings.isBlank(path) ? payload : JSON.lookupNode(payload, path.trim());
        return JSON.hasValue(actual) && JSON.nodesEqual(actual, expected);
    }

    /**
     * 本节点与委派前置节点上「算成功」的证据，新的在前。
     *
     * <p>带所属节点 id，便于取对应 Skill 的 extracts。</p>
     *
     * @param ctx 当前工具执行上下文
     * @return 成功证据列表，最新在前；可为空列表
     */
    public List<OwnedEvidence> successfulEvidenceNewestFirst(ToolExecutionContext ctx) {
        List<OwnedEvidence> heads = new ArrayList<>();
        String nodeId = ctx.getNodeId();
        for (EvidenceHead head : ctx.scope().evidence()) {
            if (isToolCallSuccess(head)) {
                heads.add(new OwnedEvidence(nodeId, head));
            }
        }

        TaskGraph graph = ctx.getGraph();
        graph.getEdges().stream()
            .filter(edge -> edge.getType() == EdgeType.DELEGATE && nodeId.equals(edge.getTo()))
            .forEach(edge -> {
                String owner = edge.getFrom();
                stateFacade.evidenceOf(ctx.getFrame().turn(), owner).stream()
                    .filter(EvidenceReader::isToolCallSuccess)
                    .forEach(head -> heads.add(new OwnedEvidence(owner, head)));
            });
        java.util.Collections.reverse(heads);
        return heads;
    }

    /**
     * 证据是否算工具调用成功。
     *
     * @param head 证据头
     * @return SUCCESS 且非业务失败
     */
    private static boolean isToolCallSuccess(EvidenceHead head) {
        if (head.status() != EvidenceStatus.SUCCESS) {
            return false;
        }

        return !Objects.equals(Boolean.FALSE, head.businessOk());
    }

    /**
     * 按抽取键取所属工具的 ExtractSpec。
     *
     * @param ctx   执行上下文
     * @param owned 带所属节点的证据
     * @param key   抽取键
     * @return 抽取声明；没有为 {@code null}
     */
    private static @Nullable ExtractSpec extractOf(ToolExecutionContext ctx, OwnedEvidence owned, String key) {
        ToolSpec tool = toolOf(ctx, owned.ownerNodeId(), owned.head().toolName());
        if (Objects.isNull(tool) || Collections.isEmpty(tool.getExtracts())) {
            return null;
        }

        for (ExtractSpec extract : tool.getExtracts()) {
            if (key.equals(extract.getKey())) {
                return extract;
            }
        }

        return null;
    }

    /**
     * 按证据所属节点的 skillKey 取 ToolSpec（委派前置节点用其自身契约）。
     *
     * @param ctx         执行上下文
     * @param ownerNodeId 证据所属节点
     * @param toolName    工具名；可空
     * @return 工具契约；找不到为 {@code null}
     */
    private static @Nullable ToolSpec toolOf(ToolExecutionContext ctx, String ownerNodeId, @Nullable String toolName) {
        if (Strings.isBlank(toolName)) {
            return null;
        }

        SkillContract skill = contractOf(ctx, ownerNodeId);
        if (Objects.isNull(skill)) {
            return null;
        }

        return skill.tool(toolName);
    }

    /**
     * 取证据所属节点的技能契约。
     *
     * @param ctx         执行上下文
     * @param ownerNodeId 证据所属节点
     * @return 技能契约；找不到为 {@code null}
     */
    private static @Nullable SkillContract contractOf(ToolExecutionContext ctx, String ownerNodeId) {
        if (Objects.equals(ownerNodeId, ctx.getNodeId())) {
            return ctx.contract();
        }

        TaskNode node = ctx.getGraph().find(ownerNodeId);
        if (Objects.isNull(node)) {
            return null;
        }

        Skill skill = ctx.getFrame().findSkill(node.getSkillKey());
        return Objects.isNull(skill) ? null : skill.contract();
    }

    /**
     * 带所属节点的证据。
     *
     * @param ownerNodeId 证据所在节点
     * @param head        证据摘要
     */
    public record OwnedEvidence(String ownerNodeId, EvidenceHead head) {

    }

    /**
     * 取值结果。
     *
     * @param value  抽出的值
     * @param source EVIDENCE 来源
     */
    public record Hit(JsonNode value, Source source) {

    }
}
