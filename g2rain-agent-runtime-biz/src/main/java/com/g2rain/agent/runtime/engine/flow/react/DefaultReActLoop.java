package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.react.ReActLoopContext;
import com.g2rain.agent.runtime.domain.react.ReActLoopResult;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.RepairDirective;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolInvoker;
import com.g2rain.agent.runtime.engine.tools.guard.ArgRepairObligation;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.dto.ChatCompletionRequest;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.schema.ChatToolCall;
import com.g2rain.model.nexus.schema.ChatToolCallFunction;
import com.g2rain.model.nexus.support.ChatMessages;
import com.g2rain.model.nexus.support.OpenAiChatClientConverter;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DefaultReActLoop：默认节点内循环。
 * <p>
 * 每轮：刷新进展 → 组提示 → 调模型 → 执行工具；遇控流退出、自动完成、停滞超限或轮次用尽则收口。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultReActLoop implements ReActLoop {

    /**
     * 运行时配置：最大轮次、无进展上限、是否自动完成等。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 模型网关：只负责推理，不在网关侧执行工具。
     */
    private final ModelGateway modelGateway;

    /**
     * 工具调用器。
     */
    private final ToolInvoker toolInvoker;

    /**
     * 运行时状态门面：每轮 flush。
     */
    private final RuntimeStateFacade stateFacade;

    /**
     * 轮次纠偏：无进展时往历史塞提示。
     */
    private final RoundRepair roundRepair;

    /**
     * 提示组装：把节点现场投影成模型请求。
     */
    private final PromptAssembler promptAssembler;

    /**
     * 跑节点内循环直至控流退出、停滞超限或轮次用尽。
     *
     * @param context 循环上下文（帧、节点、可变历史、工具表）
     * @return 循环结果（节点态、文案、挂起信息）
     */
    @Override
    public ReActLoopResult run(ReActLoopContext context) {
        int maxRounds = Math.max(1, runtimeProperties.getReact().getMaxRounds());
        int maxStall = Math.max(1, runtimeProperties.getReact().getMaxNoProgressRounds());

        // 调用方传入的可变历史；本循环就地追加 assistant / tool / 纠偏
        List<ChatMessage> history = context.historyMessages();
        List<ToolCallback> toolCallbacks = context.toolCallbacks();

        // 控流内置工具经此改节点状态；循环主线只读，不直接调 NodeControl（自动完成除外）
        ToolExecutionContext toolContext = new ToolExecutionContext(
            context.frame(), context.node(), context.scope()
        );
        JsonNode platform = context.frame().turn().platform().toJson();

        // 连续无进展计数；最近一次工具观察供下一轮 user 切片
        StallTracker stall = new StallTracker();
        String lastObservation = null;

        for (int round = 0; round < maxRounds; round++) {
            context.scope().note(NodeNoteKey.PROGRESS, new NodeProgress(
                context.scope().evidence().size(),
                !context.scope().outputs().isEmpty()
            ));
            context.scope().round(0);
            // 调模型前先刷热态，崩溃后能按当前轮次和进度便签恢复
            stateFacade.flush(context.frame().turn());

            ChatCompletionRequest request = promptAssembler.assemble(new PromptAssembleRequest(
                history, toolCallbacks, context.scope(), context.nodeId(), context.dependsOn(),
                context.skillManual(), context.node().getObjective(), userGoalOf(context), repairOf(context),
                delegateCatalogOf(context), lastObservation, context.frame().profileText(), platform,
                context.contract()
            ));

            log.info("[ReAct] round={}/{} nodeId={} historySize={} toolCount={} lastObservation={}",
                round, maxRounds, context.nodeId(), history.size(), toolCallbacks.size(), lastObservation
            );

            ChatCompletionResponse response = modelGateway.chat(request);
            ChatMessage assistant = OpenAiChatClientConverter.firstMessage(response);
            log.info("[ReAct] model reply nodeId={} round={} assistantContent={} toolCalls={}",
                context.nodeId(), round,
                Objects.nonNull(assistant) ? assistant.getContent() : null,
                Objects.nonNull(assistant) && Objects.nonNull(assistant.getToolCalls())
                    ? assistant.getToolCalls().size() : 0);

            // 无 assistant：纠偏并记停滞，超限则失败
            if (Objects.isNull(assistant)) {
                if (stalled(history, stall, maxStall, RepairReason.NO_TOOL_CALLS)) {
                    return stalledFail();
                }

                continue;
            }

            // 先写入带 tool_calls 的 assistant，再执行
            history.add(assistant);
            if (Strings.isNotBlank(assistant.getContent())) {
                appendNodeReply(context, assistant.getContent());
            }

            List<ChatToolCall> toolCalls = assistant.getToolCalls();
            // 有正文但无工具调用：同样按无进展纠偏
            if (Collections.isEmpty(toolCalls)) {
                if (stalled(history, stall, maxStall, RepairReason.NO_TOOL_CALLS)) {
                    return stalledFail();
                }

                continue;
            }

            // 参数纠偏义务：本轮调用须先过门禁，违规只写拒绝观察、不真正调工具
            RepairDirective obligation = ArgRepairObligation.read(context.scope());
            boolean blocked = false;
            Set<String> answered = new HashSet<>();
            for (ChatToolCall toolCall : toolCalls) {
                ChatToolCallFunction function = toolCall.getFunction();
                String toolName = function.getName();
                ToolCallback callback = ToolCallbacks.find(toolCallbacks, toolName);
                var violation = ArgRepairObligation.validate(obligation, callback, toolName,
                    context.scope().rounds(), runtimeProperties.getReact().getRepairMaxRounds());
                if (violation.isPresent()) {
                    log.info("[ReAct] repair blocked tool={} round={} nodeId={} violation={}", toolName, round, context.nodeId(), violation.get());
                    history.add(ChatMessages.tool(toolCall.getId()).name(toolName).content(violation.get()).build());
                    lastObservation = violation.get();
                    answered.add(toolCall.getId());
                    blocked = true;
                }
            }

            if (blocked) {
                skipUnexecuted(history, toolCalls, answered, "本波因补参义务未执行");
                stall.recordStall();
                if (stall.exceeded(maxStall)) {
                    return stalledFail();
                }

                continue;
            }

            // 逐个执行工具；按观察与控流状态决定继续、退出或停滞
            for (ChatToolCall toolCall : toolCalls) {
                ChatToolCallFunction function = toolCall.getFunction();
                String toolName = function.getName();
                String arguments = function.getArguments();
                log.info("[ReAct] invoke tool={} round={} nodeId={}", toolName, round, context.nodeId());

                Observation observation;
                ToolCallback toolCallback = ToolCallbacks.find(toolCallbacks, toolName);
                if (Objects.nonNull(toolCallback)) {
                    observation = toolInvoker.invoke(new ToolInvokeRequest(
                        toolContext, toolCallback, arguments
                    ));
                } else {
                    observation = Observation.failure("工具不在本节点可见表：[" + toolName + "]", RuntimeErrorCode.CATALOG_DENIED.code());
                }

                log.info("[ReAct] tool result tool={} round={} nodeId={} ok={} errorCode={} message={} exited={} finishRejected={}",
                    toolName, round, context.nodeId(), observation.ok(), observation.errorCode(), observation.message(), toolContext.hasExited(), toolContext.getFinishRejectedReason()
                );

                // 业务与控流统一写 role=tool
                history.add(ChatMessages.tool(toolCall.getId()).name(toolName).content(Objects.toString(observation.message(), "")).build());

                lastObservation = Objects.toString(observation.message(), "");

                // 成功且满足清义务条件：清掉参数纠偏
                ToolCallback callback = ToolCallbacks.find(toolCallbacks, toolName);
                if (observation.ok() && Objects.nonNull(obligation) && ArgRepairObligation.shouldClear(callback, toolName, obligation)) {
                    ArgRepairObligation.clear(context.scope());
                    obligation = null;
                }

                // 控流工具已让节点退出（完成/挂起/失败等）
                if (toolContext.hasExited()) {
                    skipUnexecuted(history, toolCalls, Set.of(toolCall.getId()), "本波因节点已退出未执行");
                    // 追问只在工具参数里：补记为本节点回复，续跑时 history 才能还原"问→答"
                    if (toolContext.getCheckpointType() == CheckpointType.FOLLOW_UP && Strings.isBlank(assistant.getContent())) {
                        appendNodeReply(context, toolContext.getQuestion());
                    }
                    return exited(toolContext);
                }

                // finish 被拒：纠偏后看是否停滞超限；超限失败，否则打断本波工具改下一轮
                if (Objects.nonNull(toolContext.getFinishRejectedReason())) {
                    toolContext.clearFinishRejected();
                    skipUnexecuted(history, toolCalls, Set.of(toolCall.getId()), "本波因 finish 被拒未执行");
                    if (stalled(history, stall, maxStall, RepairReason.FINISH_REJECTED)) {
                        return stalledFail();
                    }

                    break;
                }

                // 工具失败：记停滞；超限失败，否则打断本波改下一轮
                if (!observation.ok()) {
                    stall.recordStall();
                    skipUnexecuted(history, toolCalls, Set.of(toolCall.getId()), "本波因前序工具失败未执行");
                    if (stall.exceeded(maxStall)) {
                        return stalledFail();
                    }

                    break;
                }

                // 本工具成功：清零连续无进展
                stall.recordProgress();
            }
        }

        // 轮次用尽仍未由控流收口 → 失败
        return new ReActLoopResult(NodeStatus.FAILED);
    }

    /**
     * 给尚未写入 tool 消息的同波调用补一条，避免 assistant.tool_calls 缺对应 tool。
     *
     * @param history  对话历史
     * @param calls    本波 tool_calls
     * @param answered 已有 tool 消息的 call id
     * @param reason   未执行原因
     */
    private static void skipUnexecuted(List<ChatMessage> history, List<ChatToolCall> calls, Set<String> answered, String reason) {
        for (ChatToolCall call : calls) {
            if (answered.contains(call.getId())) {
                continue;
            }

            // 同波未执行的 call 也要补 tool 消息，否则下一轮 assistant.tool_calls 对不上
            ChatToolCallFunction function = call.getFunction();
            String name = Objects.nonNull(function) ? function.getName() : "tool";
            history.add(ChatMessages.tool(call.getId()).name(name).content(reason).build());
        }
    }

    /**
     * 写入纠偏消息并记一次停滞；若已超限返回 true。
     *
     * @param history  对话历史
     * @param stall    停滞跟踪
     * @param maxStall 最大无进展轮数
     * @param reason   纠偏原因
     * @return 超限则为 true
     */
    private boolean stalled(List<ChatMessage> history, StallTracker stall, int maxStall, RepairReason reason) {
        history.add(roundRepair.message(reason));
        stall.recordStall();
        return stall.exceeded(maxStall);
    }

    /**
     * 连续无进展失败回执。
     *
     * @return 失败结果
     */
    private static ReActLoopResult stalledFail() {
        return new ReActLoopResult(NodeStatus.FAILED, "连续无进展", null, null);
    }

    /**
     * 记一条本节点回复（带 round），供续跑时 history 按轮还原；空白跳过。
     *
     * @param context 循环上下文
     * @param text    回复正文或追问文案；可空
     */
    private static void appendNodeReply(ReActLoopContext context, @Nullable String text) {
        if (Strings.isBlank(text)) {
            return;
        }

        DialogueMessage message = new DialogueMessage(DialogueRole.AGENT, text);
        message.setNodeId(context.nodeId());
        message.setTaskId(context.taskId());
        message.setTraceId(context.frame().turn().traceId());
        message.setType(MessageType.AGENT_REPLY);
        message.setRound(context.scope().rounds());
        context.frame().turn().append(message);
    }

    /**
     * 按工具上下文上的节点态整理循环结果。
     *
     * @param ctx 工具执行上下文
     * @return 循环结果
     */
    private static ReActLoopResult exited(ToolExecutionContext ctx) {
        NodeStatus status = ctx.getNodeStatus();
        String message = switch (status) {
            case SUSPENDED -> ctx.getQuestion();
            case DONE -> finishOutputText(ctx.getOutput());
            default -> ctx.getFailReason();
        };
        log.info("[ReAct] exited nodeId={} status={} checkpointType={} message={} missing={} suggestions={}",
            ctx.getNodeId(), status, ctx.getCheckpointType(), message, ctx.getMissingParameters(), ctx.getSuggestions()
        );

        return new ReActLoopResult(
            status, message, ctx.getCheckpointType(), ctx.getPendingAction(),
            status == NodeStatus.SUSPENDED ? ctx.getMissingParameters() : null,
            status == NodeStatus.SUSPENDED ? ctx.getSuggestions() : null
        );
    }

    /**
     * 把 finish 输出转成节点完成文案；空或字面 {@code null} 丢弃。
     *
     * @param output finish 写出的产出；可空
     * @return 完成文案；空、空白或字面 {@code null} 则为 {@code null}
     */
    private static @Nullable String finishOutputText(@Nullable Object output) {
        if (Objects.isNull(output)) {
            return null;
        }

        String text = String.valueOf(output).trim();
        return Strings.isBlank(text) || "null".equals(text) ? null : text;
    }

    /**
     * 读本节点参数纠偏义务，转成提示文案。
     *
     * @param context 循环上下文
     * @return 纠偏提示；无则为 null
     */
    private static @Nullable String repairOf(ReActLoopContext context) {
        RepairDirective repair = context.scope().repair();
        if (Objects.isNull(repair)) {
            return null;
        }

        return repair.toPromptMessage();
    }

    /**
     * 用户目标：任务 goalSummary 优先，避免续跑补参把本轮原文当成总目标。
     *
     * @param context 循环上下文
     * @return 目标文案；可空
     */
    private static @Nullable String userGoalOf(ReActLoopContext context) {
        String goal = context.frame().turn().task()
            .map(TaskMetadata::getGoalSummary)
            .filter(Strings::isNotBlank)
            .map(String::trim)
            .orElse(null);
        if (Strings.isNotBlank(goal)) {
            return goal;
        }

        // 尚无任务摘要时才退回本轮原文，避免续跑补参把追问当总目标
        if (Strings.isNotBlank(context.frame().userInput())) {
            return context.frame().userInput().trim();
        }

        return null;
    }

    /**
     * 可委派技能目录文案（逗号拼接）。
     *
     * @param context 循环上下文
     * @return 目录文案；无可空
     */
    private static @Nullable String delegateCatalogOf(ReActLoopContext context) {
        if (Collections.isEmpty(context.delegatableSkillKeys())) {
            return null;
        }

        return String.join(", ", context.delegatableSkillKeys());
    }

    /**
     * 节点进度便签：证据条数与是否已有 outputs。
     *
     * @param evidenceCount 当前证据条数
     * @param hasOutput     节点是否已有产出
     */
    private record NodeProgress(int evidenceCount, boolean hasOutput) {
    }
}
