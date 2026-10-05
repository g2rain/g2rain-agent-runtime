package com.g2rain.agent.runtime.domain.hydrate;

/**
 * 缺参动作：必填参数绑不上时下一步做什么。
 *
 * @author alpha
 * @since 2026/9/10
 */
public enum MissingActionType {

    /**
     * 追问用户。
     * <p>挂起当前节点，索取缺失字段后再继续。</p>
     */
    ASK_USER,

    /**
     * 调用补全工具。
     * <p>用缺参规格里的工具拉齐数据，而不是先问用户。</p>
     */
    CALL_TOOL,

    /**
     * 判定失败。
     * <p>缺参不可补救时直接失败当前节点。</p>
     */
    FAIL
}
