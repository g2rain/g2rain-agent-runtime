package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.react.ReActLoopContext;
import com.g2rain.agent.runtime.domain.react.ReActLoopResult;
import com.g2rain.agent.runtime.domain.state.NodeNoteKey;
import com.g2rain.agent.runtime.domain.state.NodeScope;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueMessage;
import com.g2rain.agent.runtime.domain.state.dialogue.DialogueRole;
import com.g2rain.agent.runtime.domain.state.dialogue.MessageType;
import com.g2rain.agent.runtime.domain.state.progress.StepStage;
import com.g2rain.agent.runtime.domain.state.task.NodeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.Outcome;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.model.ModelUse;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.ToolInvoker;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.engine.tools.finish.NodeFinisher;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.i18n.RuntimeMessage;
import com.g2rain.agent.runtime.event.StepEvents;
import com.g2rain.agent.runtime.support.ArgsBrief;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
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
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

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
@Component
@RequiredArgsConstructor
public class DefaultReActLoop implements ReActLoop {

    /**
     * 解析工具入参。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

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
     * 提示组装：把节点现场投影成模型请求。
     */
    private final PromptAssembler promptAssembler;

    /**
     * 证据读取：上游节点的查询结果。
     */
    private final EvidenceReader evidenceReader;

    /**
     * 执行步骤的记录与推送。
     */
    private final StepEvents stepEvents;

    /**
     * 节点收口：returnDirect 命中时与 finish 共用完成门槛。
     */
    private final NodeFinisher nodeFinisher;

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

        // 调用方传入的可变历史；本循环就地追加 assistant / tool / 提醒
        List<ChatMessage> history = context.historyMessages();
        List<ToolCallback> toolCallbacks = context.toolCallbacks();

        // 控流内置工具经此改节点状态；循环主线只读，不直接调 NodeControl（自动完成除外）
        ToolExecutionContext toolContext = new ToolExecutionContext(
            context.frame(), context.node(), context.scope()
        );
        JsonNode platform = context.frame().turn().platform().toJson();
        // 上游节点在本节点运行期间不再变化，循环外取一次
        var upstream = evidenceReader.upstreamExtracts(toolContext);

        // 连续无进展计数
        StallTracker stall = new StallTracker();

        for (int round = 0; round < maxRounds; round++) {
            // 下一轮开始时上一轮自动完成，节点结束时最后一轮随之结束
            stepEvents.start(StepStage.ROUND, RuntimeMessage.STEP_ROUND.text("n", String.valueOf(round + 1)),
                context.nodeId(), round + 1);
            context.scope().round(0);
            // 调模型前先刷热态，崩溃后能按当前轮次恢复
            stateFacade.flush(context.frame().turn());

            // 本轮可见工具：没有被拦下待确认的写调用时不给审批工具，免得模型抢先申请
            List<ToolCallback> offered = offered(toolCallbacks, context.scope());
            ChatCompletionRequest request = promptAssembler.assemble(new PromptAssembleRequest(
                history, offered, context.scope(), context.nodeId(), context.dependsOn(),
                context.skillManual(), context.node().getObjective(), userGoalOf(context),
                delegateCatalogOf(context, offered), context.frame().profileText(), platform,
                context.contract(), Strings.blankToNull(context.node().getTarget()), context.node().isTargetClaimed(),
                upstream
            ));

            Trace.at(TraceStage.REACT, "round")
                .kv("round", round)
                .kv("maxRounds", maxRounds)
                .kv("historySize", history.size())
                .kv("toolCount", offered.size())
                .info();

            ChatCompletionResponse response = modelGateway.chat(ModelUse.REACT, context.frame().model(), request);
            ChatMessage assistant = OpenAiChatClientConverter.firstMessage(response);

            // 无 assistant：提醒并记停滞，超限则失败
            if (Objects.isNull(assistant)) {
                if (nudged(history, stall, maxStall)) {
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
            // 有正文但无工具调用：同样提醒并记停滞
            if (Collections.isEmpty(toolCalls)) {
                if (nudged(history, stall, maxStall)) {
                    return stalledFail();
                }

                continue;
            }

            // 逐个执行工具；按观察与控流状态决定继续、退出或停滞
            for (ChatToolCall toolCall : toolCalls) {
                ChatToolCallFunction function = toolCall.getFunction();
                String toolName = function.getName();
                String arguments = function.getArguments();
                Observation observation;
                int evidenceBefore = context.scope().evidence().size();
                ToolCallback toolCallback = ToolCallbacks.find(toolCallbacks, toolName);
                if (Objects.nonNull(toolCallback)) {
                    observation = toolInvoker.invoke(new ToolInvokeRequest(
                        toolContext, toolCallback, arguments
                    ));
                } else {
                    observation = Observation.rejected(
                        RuntimeErrorCode.CATALOG_DENIED,
                        ModelHint.CATALOG_DENIED.text("tool", toolName),
                        ModelHint.NEXT_CATALOG.text()
                    );
                    Trace.at(TraceStage.TOOL, "invoked")
                        .kv("tool", toolName)
                        .kv("outcome", observation.outcome())
                        .kv("errorCode", observation.errorCode())
                        .info();
                }

                // 业务与控流统一写 role=tool；没有产生证据的调用另记为节点动作，供跨轮重建
                history.add(ChatMessages.tool(toolCall.getId()).name(toolName).content(Objects.toString(observation.message(), "")).build());
                if (context.scope().evidence().size() == evidenceBefore) {
                    remember(context, toolCall, observation.message());
                }

                // 控流工具已让节点退出（完成/挂起/失败等）
                if (toolContext.hasExited()) {
                    skipUnexecuted(history, toolCalls, Set.of(toolCall.getId()), ModelHint.SKIP_NODE_EXITED.text());
                    return exited(toolContext);
                }

                // finish 被拒（原因已在观测中）：记停滞，超限失败，否则打断本波工具改下一轮
                if (Objects.nonNull(toolContext.getFinishRejectedReason())) {
                    toolContext.clearFinishRejected();
                    skipUnexecuted(history, toolCalls, Set.of(toolCall.getId()), ModelHint.SKIP_FINISH_REJECTED.text());
                    stall.recordStall();
                    if (stall.exceeded(maxStall)) {
                        return stalledFail();
                    }

                    break;
                }

                // 按结论类型推进：非成功都打断本波改下一轮，其中停滞类记一次无进展；成功清零
                Outcome outcome = observation.outcome();
                if (!outcome.advances()) {
                    skipUnexecuted(history, toolCalls, Set.of(toolCall.getId()), ModelHint.SKIP_PREVIOUS_FAILED.text());
                    if (outcome.stalls()) {
                        stall.recordStall();
                        if (stall.exceeded(maxStall)) {
                            return stalledFail();
                        }
                    }

                    break;
                }

                stall.recordProgress();

                // 契约声明直接作答：以响应中的答复完成节点，不再回到模型；门槛未过则照常下一轮
                String reply = directReply(context, toolName, observation);
                if (Objects.nonNull(reply)) {
                    if (nodeFinisher.finish(toolContext, reply)) {
                        skipUnexecuted(history, toolCalls, Set.of(toolCall.getId()), ModelHint.SKIP_NODE_EXITED.text());
                        return exited(toolContext);
                    }

                    toolContext.clearFinishRejected();
                }
            }
        }

        // 轮次用尽仍未由控流收口 → 失败
        Trace.at(TraceStage.REACT, "exited").kv("status", NodeStatus.FAILED).kv("reason", ExitReason.MAX_ROUNDS).error();
        return new ReActLoopResult(NodeStatus.FAILED);
    }

    /**
     * 把一次没有产生证据的调用记为节点动作：内置工具、执行前被拒或推迟的调用。
     *
     * <p>入参按契约脱敏后保存，续跑时与证据一起还原为"调用 → 返回"。</p>
     *
     * @param context 循环上下文
     * @param call    模型的工具调用
     * @param message 返回给模型的说明
     */
    private void remember(ReActLoopContext context, ChatToolCall call, String message) {
        ChatToolCallFunction function = call.getFunction();
        String toolName = Objects.isNull(function) ? "tool" : function.getName();
        String raw = Objects.isNull(function) ? null : function.getArguments();
        stateFacade.act(context.scope(), NodeAction.model(
            context.scope().rounds(), call.getId(), toolName, argumentsOf(context, toolName, raw), message
        ));
    }

    /**
     * 直接作答文本：工具契约配置了 {@code returnDirect} 且业务成功时，取响应中该路径的文本。
     *
     * @param context     循环上下文
     * @param toolName    工具名
     * @param observation 本次观测
     * @return 答复文本；未配置、非成功或取不到非空文本为 {@code null}
     */
    private static @Nullable String directReply(ReActLoopContext context, String toolName, Observation observation) {
        SkillContract contract = context.contract();
        ToolSpec spec = Objects.isNull(contract) ? null : contract.tool(toolName);
        String path = Objects.isNull(spec) ? null : spec.getReturnDirect();
        JsonNode data = observation.data();
        if (Strings.isBlank(path) || observation.outcome() != Outcome.SUCCESS || Objects.isNull(data)) {
            return null;
        }

        JsonNode node = JSON.lookupNode(data, path);
        return JSON.hasValue(node) ? Strings.blankToNull(JSON.asString(node)) : null;
    }

    /**
     * 入参摘要：按工具契约脱敏并限长；不是合法 JSON 时原样保留。
     *
     * @param context  循环上下文
     * @param toolName 工具名
     * @param raw      模型给出的入参；可空
     * @return 入参摘要；没有入参为 {@code null}
     */
    private @Nullable String argumentsOf(ReActLoopContext context, String toolName, @Nullable String raw) {
        if (Strings.isBlank(raw)) {
            return null;
        }

        SkillContract contract = context.contract();
        ToolSpec spec = Objects.isNull(contract) ? null : contract.tool(toolName);
        try {
            return ArgsBrief.of(JSON.str2node(raw), spec, runtimeProperties.getState().getNodeActionMaxChars());
        } catch (RuntimeException _) {
            return raw;
        }
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
     * 模型本轮没有发起工具调用：提醒改用工具并记一次停滞；若已超限返回 true。
     *
     * @param history  对话历史
     * @param stall    停滞跟踪
     * @param maxStall 最大无进展轮数
     * @return 超限则为 true
     */
    private static boolean nudged(List<ChatMessage> history, StallTracker stall, int maxStall) {
        Trace.at(TraceStage.REACT, "no_tool_calls").info();
        history.add(ChatMessages.user(ModelHint.NEXT_USE_TOOLS.text()));
        stall.recordStall();
        return stall.exceeded(maxStall);
    }

    /**
     * 连续无进展失败回执。
     *
     * @return 失败结果
     */
    private static ReActLoopResult stalledFail() {
        Trace.at(TraceStage.REACT, "exited").kv("status", NodeStatus.FAILED).kv("reason", ExitReason.STALLED).error();
        return new ReActLoopResult(NodeStatus.FAILED, RuntimeMessage.NODE_STALLED.text(), null, null);
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
        Trace.at(TraceStage.REACT, "exited")
            .kv("status", status)
            .kv("checkpointType", ctx.getCheckpointType())
            .kv("missing", ctx.getMissingParameters())
            .info();

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
     * 用户诉求：开任务时的用户原文，摘要会丢细节；续跑补参时追问回答另经历史传入，不替换总诉求。
     *
     * @param context 循环上下文
     * @return 诉求原文；可空
     */
    private static @Nullable String userGoalOf(ReActLoopContext context) {
        return context.frame().turn().task()
            .map(TaskMetadata::getRequest)
            .filter(Strings::isNotBlank)
            .orElseGet(() -> Strings.blankToNull(Strings.trim(context.frame().userInput())));
    }

    /**
     * 本轮提供给模型的工具：审批只在有被拦下待确认的写调用时提供。
     *
     * <p>只影响模型可见列表；执行仍按全量工具查找，同一轮先写后申请的并行调用照常处理。</p>
     *
     * @param tools 节点全部工具
     * @param scope 当前节点作用域
     * @return 本轮可见工具
     */
    private static List<ToolCallback> offered(List<ToolCallback> tools, NodeScope scope) {
        if (Objects.nonNull(scope.note(NodeNoteKey.GATED_WRITE))) {
            return tools;
        }

        return ToolCallbacks.without(tools, BuiltinToolAction.APPROVAL);
    }

    /**
     * 委派说明：本轮提供了委派工具才给出何时委派与可委派技能，否则不提委派。
     *
     * @param context 循环上下文
     * @param offered 本轮可见工具
     * @return 委派说明；不能委派为 {@code null}
     */
    private static @Nullable String delegateCatalogOf(ReActLoopContext context, List<ToolCallback> offered) {
        boolean delegable = ToolCallbacks.offers(offered, BuiltinToolAction.DELEGATE);
        if (!delegable || Collections.isEmpty(context.delegatableSkillKeys())) {
            return null;
        }

        return ModelHint.DELEGATE_CATALOG.text("skills", String.join(", ", context.delegatableSkillKeys()));
    }

    /**
     * 节点内循环未经控流工具收口、直接失败的原因。
     */
    private enum ExitReason {

        /**
         * 连续无进展超过上限
         */
        STALLED,

        /**
         * 轮次用尽
         */
        MAX_ROUNDS
    }
}
