package com.g2rain.agent.runtime.support.prompt;


/**
 * 技能候选筛选提示词。
 *
 * <p>{@link #SYSTEM} 说明身份与职责；{@link #USER} 是完整的本轮指令模板，结构为
 * 任务 → 筛选规则 → 输入说明 → 输入数据 → 输出指令。本类不调模型、不改任务记忆。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class SkillCandidatePrompt {

    /**
     * 身份与职责。
     */
    public static final String SYSTEM =
        """
            你是数字员工的技能筛选器，负责从给定技能中挑出完成用户目标所需的候选技能，供后续任务规划使用。
            只依据给定信息判断，不得臆造。
            """;

    /**
     * 本轮指令模板。占位符为输入数据 JSON（input、goal、skills）。
     *
     * <pre>{@code
     * String user = SkillCandidatePrompt.USER.formatted(JSON.obj2str(view));
     * }</pre>
     */
    public static final String USER =
        """
            请根据以下规则，从技能列表中筛选完成用户目标所需的候选技能。

            【筛选规则】
            - 只能从 skills 中选择 skillKey，逐字使用，不得编造或修改。
            - 选出完成目标所需的全部技能：除直接对应目标的技能外，完成目标必须依赖的前置技能（如需先查询或先创建的对象所属的技能）也要选上。
            - 拿不准某个技能是否属于完成链路时可以保留，由后续规划决定是否使用；明显无关的技能不要选。
            - 按重要性排序：必需的前置技能在前，核心业务技能其次，其余所需技能在后；数量较多时优先保留前两类。
            - 只负责筛选和排序，不拆分步骤、不编排依赖。

            【输入说明】
            - input：本轮用户输入；空串表示没有输入。
            - goal：用户目标摘要，与 input 互补，说明要办成什么。
            - skills：可选技能列表；semanticDesc 是技能的简短描述，用于判断相关性。

            【输入数据】
            %s

            【输出】
            只输出如下格式的一个 JSON 对象，不要输出其它任何文字：
            {"candidateSkillKeys":["<skillKey>"]}
            """;

    /**
     * 常量持有者；禁止实例化。
     */
    private SkillCandidatePrompt() {

    }
}
