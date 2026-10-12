package com.g2rain.agent.runtime.enums;


import com.g2rain.common.utils.Strings;

/**
 * 用户可见文案：码 + zh-CN 默认句。后续可迁 i18n，业务只引用枚举。
 *
 * @author alpha
 * @since 2026/10/3
 */
public enum RuntimeMessage {

    TASK_GONE("task.gone", "之前的事项已结束或已过期，请重新发起。"),
    ACTION_MISSING("action.missing", "请先在页面上选择要执行的操作。"),
    ACTION_DENIED("action.denied", "当前不能执行这个操作，请按页面上的选项操作。"),
    NOTHING_PENDING("action.nothing_pending", "当前没有待确认的操作。"),
    AGENT_MISSING("agent.missing", "找不到指定的数字员工。"),
    AGENT_DISABLED("agent.disabled", "该数字员工暂未启用。"),
    AGENT_NONE("agent.none", "暂时没有合适的数字员工处理这件事，您可以换个说法或指定数字员工。"),
    ROUTE_UNCERTAIN("route.uncertain", "还有一项操作等待您确认，请确认是否继续执行。"),
    ROUTE_UNCLEAR("route.unclear", "请把这件事说具体一点。"),
    PLAN_FAILED("plan.failed", "这件事暂时处理不了，请换个说法或稍后再试。"),
    REPLY_ACK("reply.ack", "好的。"),
    REPLY_DONE("reply.done", "已完成。"),
    REPLY_NEED_INPUT("reply.need_input", "还需要您补充一些信息。"),
    REPLY_CANCELLED("reply.cancelled", "已取消。"),
    REPLY_REJECTED("reply.rejected", "已取消，未执行：{action}。如需换个做法，直接告诉我。"),
    ERROR_GENERIC("error.generic", "处理没有成功，请稍后再试。"),
    STATUS_PREPARING("status.preparing", "正在准备所需信息"),
    STEP_WAITING("step.waiting", "等您回复"),
    STEP_CANCELLED("step.cancelled", "已取消"),
    STEP_FAILED("step.failed", "未完成"),
    NODE_STALLED("node.stalled", "连续多轮没有进展，已停止处理。"),
    VERIFY_ASK("verify.ask", "上次的写操作结果不确定，请确认是否已经完成。"),
    STATUS_WORKING("status.working", "正在处理"),
    STEP_TITLE_DEFAULT("step.title.default", "处理您的请求"),
    STEP_TITLE_PREPARE("step.title.prepare", "准备{name}");

    private final String code;
    private final String zhCn;

    RuntimeMessage(String code, String zhCn) {
        this.code = code;
        this.zhCn = zhCn;
    }

    /**
     * 文案码。
     *
     * @return 码
     */
    public String code() {
        return code;
    }

    /**
     * zh-CN 默认文案。
     *
     * @return 文案
     */
    public String text() {
        return zhCn;
    }

    /**
     * 替换具名占位符 {@code {name}}。
     *
     * @param name  占位名
     * @param value 替换值
     * @return 文案
     */
    public String text(String name, String value) {
        if (Strings.isBlank(name)) {
            return zhCn;
        }
        return zhCn.replace("{" + name + "}", Strings.defaultIfBlank(value, ""));
    }
}
