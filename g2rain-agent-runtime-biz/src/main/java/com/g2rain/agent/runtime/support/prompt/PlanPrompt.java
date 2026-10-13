package com.g2rain.agent.runtime.support.prompt;


/**
 * 任务规划提示词。
 *
 * <p>{@link #SYSTEM} 说明身份与职责；{@link #USER} 是完整的本轮指令模板，结构为
 * 任务 → 职责边界 → 技能约束 → 拆分规则 → 节点字段 → 完成目标 → 依赖规则 → 输入说明 → 输入数据 → 输出指令。
 * 本类不拆节点、不写任务图。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class PlanPrompt {

    /**
     * 身份与职责。
     */
    public static final String SYSTEM =
        """
            你是数字员工的任务规划器，负责在给定的候选技能范围内，把用户目标拆成可调度的业务节点并建立依赖关系。
            只依据给定信息规划，不得臆造。
            """;

    /**
     * 本轮指令模板。占位符为输入数据 JSON（input、goal、continuitySummary、agent、skills 含 writeTools）。
     *
     * <pre>{@code
     * String user = PlanPrompt.USER.formatted(JSON.obj2str(view));
     * }</pre>
     */
    public static final String USER =
        """
            请根据以下规则，把用户目标拆成业务节点。

            【职责边界】
            - 只决定节点、节点目标、完成目标和依赖关系；参数填写和执行由节点执行环节负责。
            - 不得重新筛选或扩大候选技能范围；不得填写参数、生成 API 步骤或执行脚本。

            【技能约束】
            - 每个节点的 skillKey 必须逐字来自 skills，同一 skillKey 可以用于多个节点。
            - skillManual 只用于理解技能的能力边界和业务语义，不要抄进节点。
            - 候选技能无法完成目标时，nodes 输出空数组。

            【拆分规则】
            一个节点是一次独立、可调度的业务动作，不是技能内部的处理步骤。
            应拆分：
            - 多个独立的业务动作或业务能力协作。
            - 后续动作依赖前一动作的数据、结果或业务状态。
            - 不同步骤需要独立调度、重试、暂停或人工确认。
            - 目标包含多个写操作（新增、修改、删除、状态变更等）：每个写操作一个节点。
            不应拆分：
            - 技能内部的处理步骤。
            - 普通的推理和判断。
            - 数据整理、格式转换等非独立业务动作。
            在完整覆盖目标的前提下，生成最少的必要节点。

            【节点字段】
            - nodeId：图内唯一。
            - title：简短明确的业务动作名称；一个节点只写一个动作，不描述分支或内部实现。
            - objective：本节点可验收的业务目标，只描述一次业务动作；不写工具、参数、API 或执行步骤，不整段粘贴 input 原文。
            - skillKey：所用技能的 skillKey。
            - dependsOn：直接前置节点的 nodeId 列表；没有前置时为 []。
            - target：本节点的完成目标，见【完成目标】；没有时省略。

            【完成目标】
            - 节点要完成一次写操作时，target 填该写操作对应的工具名，必须逐字来自该技能的 writeTools。
            - 节点只查询、汇总或回答，没有写操作时，不填 target。
            - 一个节点最多一个 target；节点执行时，target 工具调用成功才算完成。

            【依赖规则】
            - dependsOn 只能引用本图中的 nodeId。
            - 节点需要另一技能先提供数据、结果或状态时，必须先生成对应节点，并在 dependsOn 中引用。
            - 有多个直接前置节点时全部列出。
            - dependsOn 只表示真实的业务依赖，不要为了串行而添加；没有依赖的节点可以并行。
            - 不得自依赖或循环依赖。

            【输入说明】
            - input：本轮用户输入；空串表示没有输入。
            - goal：任务目标摘要，节点要对准它；空串表示没有单独摘要。
            - continuitySummary：上一个任务的综述；接续前情时让新计划接上已有结论，避免重做或冲突。
            - agent.profileText：数字员工的身份与职责边界，只用于理解任务，不代表额外能力。
            - skills：候选技能、操作手册（skillManual）和写工具（writeTools，name 为工具名，label 为名称）；writeTools 为空表示该技能没有写操作。

            【输入数据】
            %s

            【输出】
            只输出如下格式的一个 JSON 对象，不要输出其它任何文字；能完成目标时 nodes 至少包含一个节点，无法完成时 nodes 为空数组：
            {"nodes":[{"nodeId":"n1","title":"<业务动作名称>","objective":"<可验收的业务目标>","skillKey":"<skillKey>","dependsOn":[],"target":"<写工具名，无写操作时省略>"}]}
            """;

    /**
     * 常量持有者；禁止实例化。
     */
    private PlanPrompt() {

    }
}
