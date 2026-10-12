package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.hydrate.InputSpec;
import com.g2rain.agent.runtime.domain.hydrate.OutputSpec;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.DelegateResult;
import com.g2rain.agent.runtime.domain.state.task.EvidenceHead;
import com.g2rain.agent.runtime.domain.state.task.PendingAction;
import com.g2rain.agent.runtime.domain.state.task.Prior;
import com.g2rain.agent.runtime.domain.state.task.TaskCheckpoint;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把节点现场收成结构化投影 record，供提示组装器序列化。
 *
 * <p>按固定优先级生成块，空块省略。超长值替换为 {@link ReactProjection.OmittedValue}。
 * {@code delegate_result} 读出后会从节点便签清掉，避免下一轮重复投影。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Slf4j
@Component
public class ReactProjectionFactory {

    /**
     * 序列化投影值与反序列化委托结果。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 当前节点的执行提示。
     */
    private final PhaseHint phaseHint;

    /**
     * 投影长度阈值。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 依赖节点 outputs 的读取门面。
     */
    private final RuntimeStateFacade runtimeStateFacade;

    /**
     * @param phaseHint          当前节点的执行提示
     * @param runtimeProperties  投影阈值
     * @param runtimeStateFacade 依赖 outputs
     */
    public ReactProjectionFactory(PhaseHint phaseHint, RuntimeProperties runtimeProperties, RuntimeStateFacade runtimeStateFacade) {
        this.phaseHint = phaseHint;
        this.runtimeProperties = runtimeProperties;
        this.runtimeStateFacade = runtimeStateFacade;
    }

    /**
     * 按固定优先级生成投影块；没有内容的块直接省略。
     *
     * @param request 组装入参
     * @return 非空投影块
     */
    public List<ReactProjection.TaggedBlock> blocks(PromptAssembleRequest request) {
        List<ReactProjection.TaggedBlock> blocks = new ArrayList<>();
        add(blocks, "node_goal", nodeGoal(request));
        add(blocks, "checkpoint", pendingCheckpoint(request));
        add(blocks, "input_schema", inputSchema(request));
        add(blocks, "tool_catalog", ToolCatalogProjector.of(request.toolCallbacks(), request.contract()));
        add(blocks, "evidence_digest", evidenceDigest(request));
        add(blocks, "upstream_evidence", upstreamEvidence(request));
        add(blocks, "output_schema", outputSchema(request));
        add(blocks, "dependency_outputs", dependencyOutputs(request));
        add(blocks, "shared", shared(request));
        add(blocks, "prior", prior(request));
        add(blocks, "delegate_result", delegateResult(request));
        return blocks;
    }

    /**
     * 空载荷或空列表不入投影，避免给模型空标签块。
     *
     * @param blocks  已收集的投影块
     * @param tag     标签名
     * @param payload 块内容；可空，空则跳过
     */
    private static void add(List<ReactProjection.TaggedBlock> blocks, String tag, @Nullable Object payload) {
        if (Objects.isNull(payload)) {
            return;
        }

        if (payload instanceof List<?> list && Collections.isEmpty(list)) {
            return;
        }

        blocks.add(new ReactProjection.TaggedBlock(tag, payload));
    }

    /**
     * 组装 {@code node_goal}：节点标识、目标、技能手册和平台上下文。
     *
     * @param request 组装入参
     * @return 节点目标投影
     */
    private ReactProjection.NodeGoalView nodeGoal(PromptAssembleRequest request) {
        SkillContract contract = request.contract();
        JsonNode platform = request.ambient();
        if (Objects.nonNull(platform) && platform.isEmpty()) {
            platform = null;
        }

        return new ReactProjection.NodeGoalView(
            request.nodeId(),
            Strings.blankToNull(request.objective()),
            Strings.blankToNull(request.userGoal()),
            Strings.blankToNull(request.skillManual()),
            Objects.isNull(contract) ? null : contract.modeOrDefault(),
            request.targetClaimed() ? null : request.target(),
            phaseHint.hint(request.node(), contract, request.target(), request.targetClaimed()),
            Strings.blankToNull(request.delegateCatalog()),
            Strings.blankToNull(request.profileText()),
            platform
        );
    }

    /**
     * 组装 {@code input_schema}：契约声明字段与已绑定值。
     *
     * @param request 组装入参
     * @return 输入投影；声明与绑定都空时为 {@code null}
     */
    private ReactProjection.@Nullable InputSchemaView inputSchema(PromptAssembleRequest request) {
        List<ReactProjection.InputFieldView> declared = declaredInputs(request);
        List<ReactProjection.SourcedEntry> bound = sourcedEntries(request.node().inputs());
        if (Collections.isEmpty(declared) && Collections.isEmpty(bound)) {
            return null;
        }

        return new ReactProjection.InputSchemaView(emptyToNull(declared), emptyToNull(bound));
    }

    /**
     * 把契约 inputs 转成字段视图，并带上当前绑定值。
     *
     * @param request 组装入参
     * @return 声明字段列表；无契约或无 inputs 时为空列表
     */
    private List<ReactProjection.InputFieldView> declaredInputs(PromptAssembleRequest request) {
        SkillContract contract = request.contract();
        if (Objects.isNull(contract) || contract.getInputs().isEmpty()) {
            return List.of();
        }

        return contract.getInputs().entrySet().stream()
            .map(entry -> inputField(entry.getKey(), entry.getValue(), request.node().inputs().get(entry.getKey())))
            .toList();
    }

    /**
     * 单个 input 字段：声明、绑定值和是否缺失。
     *
     * @param name  字段名
     * @param spec  契约声明
     * @param bound 当前绑定值；未绑定时可空
     * @return 字段投影
     */
    private ReactProjection.InputFieldView inputField(String name, InputSpec spec, @Nullable Sourced<?> bound) {
        boolean missing = Objects.isNull(bound) && spec.isRequired();
        return new ReactProjection.InputFieldView(
            name,
            spec.isRequired(),
            Strings.blankToNull(spec.getFrom()),
            Strings.blankToNull(spec.getProvider()),
            Objects.isNull(bound) ? null : clip(bound.value()),
            Objects.isNull(bound) ? null : bound.source().type(),
            missing ? Boolean.TRUE : null
        );
    }

    /**
     * 组装 {@code output_schema}：契约声明字段与已写出值。
     *
     * @param request 组装入参
     * @return 产出投影；声明与写出都空时为 {@code null}
     */
    private ReactProjection.@Nullable OutputSchemaView outputSchema(PromptAssembleRequest request) {
        List<ReactProjection.OutputFieldView> declared = declaredOutputs(request.contract());
        List<ReactProjection.SourcedEntry> written = sourcedEntries(request.node().outputs());
        if (Collections.isEmpty(declared) && Collections.isEmpty(written)) {
            return null;
        }

        return new ReactProjection.OutputSchemaView(emptyToNull(declared), emptyToNull(written));
    }

    /**
     * 把契约 outputs 转成字段视图。
     *
     * @param contract 技能契约；可空
     * @return 声明字段列表；无契约或无 outputs 时为空列表
     */
    private static List<ReactProjection.OutputFieldView> declaredOutputs(@Nullable SkillContract contract) {
        if (Objects.isNull(contract) || contract.getOutputs().isEmpty()) {
            return List.of();
        }

        return contract.getOutputs().entrySet().stream()
            .map(entry -> outputField(entry.getKey(), entry.getValue()))
            .toList();
    }

    /**
     * 单个 output 字段声明。
     *
     * @param name 字段名
     * @param spec 契约声明
     * @return 字段投影
     */
    private static ReactProjection.OutputFieldView outputField(String name, OutputSpec spec) {
        return new ReactProjection.OutputFieldView(
            name,
            spec.typeOrDefault(),
            spec.isRequired(),
            Collections.isEmpty(spec.getFields()) ? null : spec.getFields(),
            Objects.isNull(spec.getMinItems()) ? null : spec.minItemsOrDefault(),
            Strings.blankToNull(spec.getHint()),
            spec.getShare(),
            Objects.isNull(spec.getEntity()) ? null : Strings.blankToNull(spec.getEntity().getType())
        );
    }

    /**
     * 组装 {@code evidence_digest}：本节点证据头摘要。
     *
     * @param request 组装入参
     * @return 证据列表；无证据时为 {@code null}
     */
    private @Nullable List<ReactProjection.EvidenceItemView> evidenceDigest(PromptAssembleRequest request) {
        if (Collections.isEmpty(request.node().evidence())) {
            return null;
        }

        return request.node().evidence().stream().map(ReactProjectionFactory::evidenceItem).toList();
    }

    /**
     * 组装 {@code upstream_evidence}：上游节点查询结果原文；超长改占位，可按 evidenceId 再取。
     *
     * @param request 组装入参
     * @return 上游结果列表；没有时为 {@code null}
     */
    private @Nullable List<ReactProjection.UpstreamEvidenceView> upstreamEvidence(PromptAssembleRequest request) {
        if (Collections.isEmpty(request.upstream())) {
            return null;
        }

        int max = runtimeProperties.getReact().getWorkspaceSliceMaxChars();
        return request.upstream().stream()
            .map(item -> new ReactProjection.UpstreamEvidenceView(item.nodeId(), item.evidenceId(), item.tool(),
                item.key(), item.path(), clip(item.value(), max)))
            .toList();
    }

    /**
     * 单条证据头转投影项。
     *
     * @param head 证据头
     * @return 证据投影
     */
    private static ReactProjection.EvidenceItemView evidenceItem(EvidenceHead head) {
        List<ReactProjection.ExtractCountView> counts = List.of();
        if (Objects.nonNull(head.extractCounts()) && !head.extractCounts().isEmpty()) {
            counts = head.extractCounts().entrySet().stream()
                .map(entry -> new ReactProjection.ExtractCountView(entry.getKey(), entry.getValue()))
                .toList();
        }

        return new ReactProjection.EvidenceItemView(
            head.evidenceId(),
            head.toolName(),
            head.status(),
            head.businessOk(),
            head.empty(),
            emptyToNull(counts),
            head.failureKind(),
            Strings.blankToNull(head.argsBrief()),
            Strings.blankToNull(head.resultBrief())
        );
    }

    /**
     * 组装 {@code dependency_outputs}：依赖节点已写出的值。
     *
     * @param request 组装入参
     * @return 依赖产出列表；无依赖或全空时为 {@code null}
     */
    private @Nullable List<ReactProjection.SourcedEntry> dependencyOutputs(PromptAssembleRequest request) {
        if (Collections.isEmpty(request.dependsOn())) {
            return null;
        }

        return emptyToNull(sourcedEntries(runtimeStateFacade.outputsOf(request.node().turn(), request.dependsOn())));
    }

    /**
     * 组装 {@code shared}：任务记忆与会话记忆中的共享值。
     *
     * @param request 组装入参
     * @return 共享值投影；两边都空时为 {@code null}
     */
    private ReactProjection.@Nullable SharedView shared(PromptAssembleRequest request) {
        List<ReactProjection.SourcedEntry> task = sourcedEntries(request.node().turn().shared());
        List<ReactProjection.SourcedEntry> session = sourcedEntries(request.node().turn().sessionShared());
        if (Collections.isEmpty(task) && Collections.isEmpty(session)) {
            return null;
        }

        return new ReactProjection.SharedView(emptyToNull(task), emptyToNull(session));
    }

    /**
     * 组装 {@code prior}：开任务时写入的任务前情切片。
     *
     * @param request 组装入参
     * @return 任务前情投影；没有 Prior 或全空时为 {@code null}
     */
    private ReactProjection.@Nullable PriorView prior(PromptAssembleRequest request) {
        Prior prior = request.node().turn().prior().orElse(null);
        if (Objects.isNull(prior)) {
            return null;
        }

        List<ReactProjection.PriorTaskView> tasks = prior.tasks().stream()
            .map(record -> new ReactProjection.PriorTaskView(
                record.taskId(), Strings.blankToNull(record.goal()), record.status(), Strings.blankToNull(record.summary())
            ))
            .toList();
        List<ReactProjection.PriorEntityView> entities = prior.entities().entrySet().stream()
            .map(entry -> new ReactProjection.PriorEntityView(
                entry.getKey(), entry.getValue().displayName(), Strings.blankToNull(entry.getValue().taskId())
            ))
            .toList();
        if (Strings.isBlank(prior.reason()) && Collections.isEmpty(tasks) && Collections.isEmpty(entities)) {
            return null;
        }

        return new ReactProjection.PriorView(Strings.blankToNull(prior.reason()), emptyToNull(tasks), emptyToNull(entities));
    }

    /**
     * 组装 {@code checkpoint}：仍在等待用户的断点。
     *
     * <p>节点被唤醒时断点已消费，这里只会是仍在等待用户回复的断点。</p>
     *
     * @param request 组装入参
     * @return 断点投影；没有时为 {@code null}
     */
    private static ReactProjection.@Nullable CheckpointView pendingCheckpoint(PromptAssembleRequest request) {
        return request.node().turn().checkpoint().map(ReactProjectionFactory::checkpoint).orElse(null);
    }

    /**
     * 当前断点转投影视图。
     *
     * @param checkpoint 任务检查点
     * @return 断点投影
     */
    private static ReactProjection.CheckpointView checkpoint(TaskCheckpoint checkpoint) {
        PendingAction pending = checkpoint.getPendingAction();
        String pendingTool = Objects.isNull(pending) ? null : Strings.blankToNull(pending.toolName());
        return new ReactProjection.CheckpointView(
            checkpoint.getType(),
            Strings.blankToNull(checkpoint.getQuestion()),
            emptyToNull(checkpoint.getAllowedActions()),
            emptyToNull(checkpoint.getExpectedFields()),
            pendingTool
        );
    }

    /**
     * 组装 {@code delegate_result}：读出后从节点便签清掉。
     *
     * @param request 组装入参
     * @return 委派结果投影；无便签或无法解析时为 {@code null}
     */
    private ReactProjection.@Nullable DelegateResultView delegateResult(PromptAssembleRequest request) {
        Object raw = request.node().note(NodeNoteKey.DELEGATE_RESULT);
        if (Objects.isNull(raw)) {
            return null;
        }

        DelegateResult result = typedDelegateResult(raw);
        // 一次性便签：投影后清掉，避免下一轮重复展示
        request.node().note(NodeNoteKey.DELEGATE_RESULT, null);
        if (Objects.isNull(result)) {
            return null;
        }

        return new ReactProjection.DelegateResultView(
            Strings.blankToNull(result.skillKey()),
            result.status().name(),
            Strings.blankToNull(result.errorCode()),
            clipText(result.errorMessage())
        );
    }

    /**
     * 把便签原值转成 {@link DelegateResult}。
     *
     * @param raw 节点便签原值
     * @return 类型化结果；无法转换时为 {@code null}
     */
    private static @Nullable DelegateResult typedDelegateResult(Object raw) {
        if (raw instanceof DelegateResult typed) {
            return typed;
        }

        try {
            return JSON.str2obj(JSON.obj2str(raw), DelegateResult.class);
        } catch (RuntimeException ex) {
            log.warn("[Projection] delegateResult convert failed type={}", raw.getClass().getName(), ex);
            return null;
        }
    }

    /**
     * 按配置截断文本摘要。
     *
     * @param text 原文；可空
     * @return 截断后的文本；空白输入为 {@code null}
     */
    private @Nullable String clipText(@Nullable String text) {
        if (Strings.isBlank(text)) {
            return null;
        }
        return RuntimeUtils.clip(text.trim(), runtimeProperties.getReact().getValueSummaryMaxChars());
    }

    /**
     * 把 sourced 值表转成投影条目，值按配置截断。
     *
     * @param values 路径到 sourced 值
     * @return 投影条目列表；输入为空时为空列表
     */
    private List<ReactProjection.SourcedEntry> sourcedEntries(Map<String, Sourced<?>> values) {
        return values.entrySet().stream()
            .map(entry -> new ReactProjection.SourcedEntry(
                entry.getKey(),
                clip(entry.getValue().value()),
                entry.getValue().source().type(),
                Strings.blankToNull(entry.getValue().source().ref())
            ))
            .toList();
    }

    /**
     * 把任意值收成 JSON 节点；超长改成占位，不截半截 JSON。
     *
     * @param value 原值；可空
     * @return JSON 节点；空值时为 {@code {}}
     */
    private JsonNode clip(@Nullable Object value) {
        return clip(value, runtimeProperties.getReact().getValueSummaryMaxChars());
    }

    /**
     * 把任意值收成 JSON 节点；超过上限改成占位，不截半截 JSON。
     *
     * @param value 原值；可空
     * @param max   字符上限
     * @return JSON 节点；空值时为 {@code {}}
     */
    private JsonNode clip(@Nullable Object value, int max) {
        JsonNode node = JSON.obj2node(value);
        if (Objects.isNull(node) || node.isNull()) {
            return JSON.str2node("{}");
        }

        String raw = JSON.obj2str(node);
        if (raw.length() <= max) {
            return node;
        }

        // 超长不截半截 JSON，改成占位统计
        Integer items = node.isArray() ? node.size() : null;
        Integer fields = node.isObject() ? node.size() : null;
        return JSON.obj2node(new ReactProjection.OmittedValue(true, raw.length(), items, fields));
    }

    /**
     * 空列表投影为 {@code null}，配合 {@code NON_NULL} 省略字段。
     *
     * @param values 原列表
     * @return 非空列表原样返回；空列表为 {@code null}
     */
    private static <T> @Nullable List<T> emptyToNull(List<T> values) {
        return Collections.isEmpty(values) ? null : values;
    }
}
