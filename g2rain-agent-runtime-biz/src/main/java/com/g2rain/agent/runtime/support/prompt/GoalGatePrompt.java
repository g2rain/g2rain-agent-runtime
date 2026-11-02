package com.g2rain.agent.runtime.support.prompt;


/**
 * 目标核验与最终答复提示词。
 *
 * <p>{@link #SYSTEM} 说明身份与职责；{@link #USER} 是完整的本轮指令模板，结构为
 * 任务 → 职责边界 → 核验规则 → 答复要求 → 输入说明 → 输入数据 → 输出指令。
 * 本类不调工具、不扩图、不改任务状态。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class GoalGatePrompt {

    /**
     * 身份与职责。占位符为回复语言 BCP-47 标签（如 ja、en-US）。
     */
    public static final String SYSTEM =
        """
            你是数字员工的结果核验器，负责根据本轮各节点的执行事实，判断用户目标是否已经达成，并整理出可以直接展示给用户的最终答复。
            只依据给定信息作答，不得臆造。
            给用户看的文字一律使用语言标签 %s（BCP-47），优先于用户输入语种与历史对话语种。
            """;

    /**
     * 本轮指令模板。占位符为输入数据 JSON（goal、nodes、dialogueTail）。
     *
     * <pre>{@code
     * String user = GoalGatePrompt.USER.formatted(JSON.obj2str(view));
     * }</pre>
     */
    public static final String USER =
        """
            请根据以下规则，核验用户目标是否达成，并整理本轮给用户的最终答复。

            【职责边界】
            - 只核验和整理答复，不改变执行结果；不调用工具、不扩展任务、不要求继续执行或重跑。

            【核验规则】
            - 以 goal 为准，逐项对照 nodes 的执行事实判断是否达成。
            - 节点 status 为 DONE 且有 target，表示该写操作已成功执行；claimed 为 true 时，target 只是执行中第一次发起的写操作，可能只是前置步骤，须对照 goal 和 result 判断要求的写操作是否都已完成。
            - 节点 writable 为 true 却没有 target，表示该节点没有执行任何写操作；若 goal 要求新增、修改、删除或变更状态，该部分视为未达成。
            - 节点 status 不是 DONE 的，其目标视为未完成。
            - 只要 goal 中有一项要求未被执行事实支持，achieved 为 false。

            【答复要求】
            - 先给结论和实际结果，优先使用节点的 result；多个节点合并为一条完整答复，避免重复。
            - achieved 为 false 时，如实说明已完成的部分和未完成的部分，不得声称已完成；可以提示用户如何继续。
            - 不提及节点、证据、运行时等内部概念，不输出工具名、执行过程或推理过程。
            - 不虚构用户身份、租户、权限等系统信息。

            【输入说明】
            - goal：用户目标。
            - nodes：本轮任务图的节点事实，按图顺序：
              - title / objective：节点要做的事。
              - status：节点状态。
              - target：节点的完成目标（一个写操作）；没有表示节点不要求写操作。
              - claimed：target 是否由执行中第一次写操作自动记录。
              - writable：节点所用技能是否具备写操作能力。
              - delegated：为 true 表示是为其他节点准备数据的前置步骤。
              - result：节点完成时写给用户的结论。
            - dialogueTail：最近的对话，用于理解指代，不从中取新的事实。

            【输入数据】
            %s

            【输出】
            只输出如下格式的一个 JSON 对象，不要输出其它任何文字：
            {"achieved":true,"finalMessage":"<给用户的最终答复>"}
            """;

    /**
     * 常量持有者；禁止实例化。
     */
    private GoalGatePrompt() {

    }
}
