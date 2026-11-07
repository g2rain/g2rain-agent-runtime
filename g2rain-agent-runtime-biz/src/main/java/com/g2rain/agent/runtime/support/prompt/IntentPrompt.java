package com.g2rain.agent.runtime.support.prompt;

/**
 * 本轮意图分类提示词。
 *
 * <p>{@link #SYSTEM} 说明身份与职责；{@link #USER} 是完整的本轮指令模板，结构为
 * 任务 → 场景 → 规则 → 上下文 → 用户输入 → 输出指令。场景规则中的可选模式与
 * {@link com.g2rain.agent.runtime.domain.decision.TurnScene} 保持一致。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class IntentPrompt {

    /**
     * 身份与职责。占位符为回复语言 BCP-47 标签（如 ja、en-US）。
     */
    public static final String SYSTEM =
        """
            你是企业智能助手的意图路由器，负责判断用户本轮输入如何推进任务，并选择执行的数字员工。
            只依据给定信息判断，不得臆造。
            给用户看的文字（userReply）一律使用语言标签 %s（BCP-47），优先于用户输入语种与历史对话语种。
            """;

    /**
     * 本轮指令模板。占位符依次为：当前场景、会话上下文 JSON、本轮用户输入。
     *
     * <pre>{@code
     * String user = IntentPrompt.USER.formatted(scene.name(), contextJson, userInput);
     * }</pre>
     */
    public static final String USER =
        """
            请根据以下规则和会话上下文，判断本轮用户输入如何推进任务，并在需要时选择执行的数字员工。

            【当前场景】
            %s

            【推进规则】
            按当前场景对应的一组规则判断，从上到下取第一条符合的。mode 取箭头右侧的值（如 START_FRESH），FRESH、CONTINUITY、ACTIVE_TASK、WRITE_PENDING 是场景名，不能作为 mode。

            FRESH（新对话）：
            - 需要办理事项 → START_FRESH
            - 寒暄、致谢、闲聊等无需办理的内容 → REPLY_ONLY

            CONTINUITY（无进行中的任务，上一个任务的摘要见 continuation）：
            - 新的事项明确依赖上一个任务的结果 → START_WITH_CONTEXT
            - 需要办理的其它事项 → START_FRESH
            - 寒暄、致谢、闲聊等无需办理的内容 → REPLY_ONLY

            ACTIVE_TASK（有进行中的任务，见 activeTask）：
            - 只取消当前任务、不再办理 → CANCEL_TASK
            - 明确要求换一个数字员工 → SWITCH_AGENT（agentLocked=true 时不可用）
            - 放弃当前任务、改办别的事 → REPLACE_TASK
            - 仍在推进当前任务，包括回答它的提问 → CONTINUE_TASK
            - 与任务无关的寒暄、致谢、闲聊 → REPLY_ONLY

            WRITE_PENDING（有进行中的任务，且有一项操作等待用户确认，见 activeTask）：
            - 不执行待确认的操作，也没有提出别的事项 → CANCEL_TASK
            - 不执行待确认的操作，改办别的事（包括提出新的问题或要求）→ REPLACE_TASK
            - 明确要求换一个数字员工 → SWITCH_AGENT（agentLocked=true 时不可用）
            - 同意执行、回答确认问题，或要求修改待确认的操作 → CONTINUE_TASK
            - 与任务无关的寒暄、致谢、闲聊 → REPLY_ONLY

            【选人规则】
            - 只有 START_FRESH、START_WITH_CONTEXT、SWITCH_AGENT 需要选人，从 agentCandidates 中选一个 agentId；其它模式 agentId 为 null。
            - 按候选职责和 skillCatalog 做语义匹配；表达口语化或模糊时选最贴近者，难以区分时选排在前面的；只有一个候选时直接选它。
            - START_WITH_CONTEXT 优先选择 continuityAgentId。
            - agentLocked=true 表示已指定数字员工（agentCandidates 中唯一的一项）；寒暄或说法模糊但可能相关时，视为它能处理。
            - 只有诉求与候选职责明显无关时，agentId 为 null，并在 userReply 中说明可以办理的事项，举 1～2 个例子。

            【userReply】
            - REPLY_ONLY：给用户的简短回复。
            - 无法匹配数字员工：说明可以办理的事项。
            - 其它情况为 null。

            【会话上下文】
            %s

            【本轮用户输入】
            %s

            【输出】
            请按当前场景的推进规则判断本轮用户输入，并按选人规则选择数字员工，只输出如下格式的一个 JSON 对象，不要输出其它任何文字：
            {"mode":"<推进规则中箭头右侧的值>","agentId":<agentId 或 null>,"goalSummary":"<一句话概括用户目标>","userReply":<文本或 null>}
            """;

    /**
     * 常量持有者；禁止实例化。
     */
    private IntentPrompt() {

    }
}
