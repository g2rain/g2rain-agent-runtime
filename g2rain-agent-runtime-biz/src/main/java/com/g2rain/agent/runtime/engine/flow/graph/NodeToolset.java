package com.g2rain.agent.runtime.engine.flow.graph;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.GraphFrame;
import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.Skill;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.mcp.McpToolCallbackProvider;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCallbackProvider;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 节点工具集：按节点筛选内置工具，并挂载技能声明的 MCP 工具。
 *
 * @author alpha
 * @since 2026/10/12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeToolset {

    /**
     * 内置工具回调提供方。
     */
    private final BuiltinToolCallbackProvider builtinTools;

    /**
     * MCP 同步客户端：按技能声明装配外部工具。
     */
    private final McpSyncClient mcpSyncClient;

    /**
     * {@code mcp.max-retries}、委派层级上限。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 组装本节点可用工具：内置工具 + 技能声明的 MCP 工具。
     *
     * <p>业务 MCP 按 {@code skill.toolNames()} 挂载；内置控流始终保留。
     * 节点完成目标（target）只供完成判定锚定，不收窄可见工具。
     *
     * @param node  当前节点
     * @param frame 当前图执行帧
     * @return 内置工具加技能 MCP 工具；无技能时仅内置
     */
    public List<ToolCallback> of(TaskNode node, GraphFrame frame) {
        // 内置工具只给本节点用得上的：没有可委派技能不给委派，产出无人使用不给写产出
        List<ToolCallback> result = Arrays.stream(builtinTools.getToolCallbacks())
            .filter(tool -> offers(tool, node, frame))
            .collect(Collectors.toCollection(ArrayList::new));

        String sk = node.getSkillKey();
        if (Strings.isBlank(sk)) {
            return result;
        }

        Skill skill = frame.findSkill(sk);
        if (Objects.isNull(skill) || Collections.isEmpty(skill.toolNames())) {
            return result;
        }

        // 可调用以 toolNames 为准；contract.tools 仅可选配置
        LinkedHashSet<String> tools = new LinkedHashSet<>(skill.toolNames());
        if (tools.isEmpty()) {
            return result;
        }
        try {
            var mcpProvider = new McpToolCallbackProvider(
                mcpSyncClient, tools, runtimeProperties.getMcp().getMaxRetries()
            );
            result.addAll(Arrays.asList(mcpProvider.getToolCallbacks()));
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("MCP tools load failed nodeId={}", node.getNodeId(), ex);
            throw new BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, ex, "MCP Registry");
        }

        return result;
    }

    /**
     * 内置工具是否提供给本节点。
     *
     * @param tool  内置工具回调
     * @param node  当前节点
     * @param frame 当前图执行帧
     * @return 本节点用得上
     */
    private boolean offers(ToolCallback tool, TaskNode node, GraphFrame frame) {
        if (ToolCallbacks.is(tool, BuiltinToolAction.DELEGATE)) {
            return canDelegate(node, frame);
        }
        if (ToolCallbacks.is(tool, BuiltinToolAction.OUTPUT)) {
            return outputsUsed(node, frame);
        }
        return true;
    }

    /**
     * 有可委派技能且未到委派层级上限。
     *
     * @param node  当前节点
     * @param frame 当前图执行帧
     * @return 可以委派
     */
    private boolean canDelegate(TaskNode node, GraphFrame frame) {
        boolean delegatable = frame.skills().stream()
            .map(Skill::contract)
            .filter(Objects::nonNull)
            .anyMatch(SkillContract::isDelegatable);
        return delegatable && node.getDelegateDepth() + 1 <= runtimeProperties.getState().getDelegateMaxDepth();
    }

    /**
     * 产出有人使用：契约产出必填、共享或沉淀实体。下游节点直接读上游证据，不靠产出中转查询结果。
     *
     * @param node  当前节点
     * @param frame 当前图执行帧
     * @return 产出有用处
     */
    private static boolean outputsUsed(TaskNode node, GraphFrame frame) {
        Skill skill = frame.findSkill(node.getSkillKey());
        SkillContract contract = Objects.isNull(skill) ? null : skill.contract();
        return Objects.nonNull(contract) && contract.getOutputs().values().stream()
            .anyMatch(output -> output.isRequired() || Objects.nonNull(output.getShare())
                || Objects.nonNull(output.getEntity()));
    }
}
