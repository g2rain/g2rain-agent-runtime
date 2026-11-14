package com.g2rain.agent.runtime.i18n;


import com.g2rain.agent.runtime.support.RequestLocale;
import com.g2rain.common.utils.Strings;

/**
 * Agent Runtime 用户可见文案：维护稳定的 message code 和中文兜底句；多语言文案由 Infra 提供。
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
    REPLY_REJECTED_DONE("reply.rejected_done", "已完成：{steps}。"),
    ERROR_GENERIC("error.generic", "处理没有成功，请稍后再试。"),
    ERROR_MODEL_QUOTA("error.model_quota", "模型服务额度不足，请联系管理员。"),
    ERROR_MODEL_AUTH("error.model_auth", "模型访问密钥无效，请联系管理员。"),
    ERROR_MODEL_PARAM("error.model_param", "内容过长或参数不符合模型要求，请精简后再试。"),
    ERROR_MODEL_CONFIG("error.model_config", "模型未配置或未启用，请联系管理员。"),
    ERROR_MODEL_RATE_LIMIT("error.model_rate_limit", "模型繁忙，请稍后重试。"),
    ERROR_MODEL_TIMEOUT("error.model_timeout", "模型响应超时，请稍后重试。"),
    ERROR_MODEL_RESPONSE("error.model_response", "模型服务返回异常，请稍后重试或联系管理员。"),
    STATUS_PREPARING("status.preparing", "正在准备所需信息"),
    STEP_WAITING("step.waiting", "等您回复"),
    STEP_CANCELLED("step.cancelled", "已取消"),
    STEP_FAILED("step.failed", "未完成"),
    NODE_STALLED("node.stalled", "「{step}」连续多轮没有进展，已停止处理。"),
    NODE_STALLED_LAST("node.stalled_last", "最后一次：{tool}未成功（{code}）。"),
    NODE_HANDED_OVER("node.handed_over", "已完成「{step}」，「{owner}」由后续步骤执行。"),
    VERIFY_ASK("verify.ask", "上次的写操作结果不确定，请确认是否已经完成。"),
    STATUS_WORKING("status.working", "正在处理"),
    STEP_TITLE_DEFAULT("step.title.default", "处理您的请求"),
    STEP_TITLE_PREPARE("step.title.prepare", "准备{name}"),
    STEP_ROUTE("step.route", "理解问题"),
    STEP_ROUTE_AGENT("step.route.agent", "选中「{agent}」"),
    STEP_PLAN("step.plan", "规划步骤"),
    STEP_PLAN_DONE("step.plan.done", "规划 {count} 个节点"),
    STEP_ROUND("step.round", "第 {n} 轮"),
    STEP_VERIFY("step.verify", "校验结果"),
    STEP_VERIFY_MET("step.verify.met", "目标达成"),
    STEP_VERIFY_UNMET("step.verify.unmet", "目标未完全达成"),
    STEP_VERIFY_FAILED("step.verify.failed", "校验未完成，已整理现有结果"),
    STEP_TOOL("step.tool", "调用 {name}"),
    STEP_TOOL_DEFAULT("step.tool.default", "调用工具"),
    STEP_TOOL_OK("step.tool.ok", "成功"),
    STEP_ASK("step.ask", "向您追问"),
    STEP_APPROVAL("step.approval", "申请确认"),
    STEP_DELEGATE("step.delegate", "委派：{skill}"),
    STEP_FINISH("step.finish", "完成"),
    STEP_FAIL("step.fail", "判定无法完成"),
    STEP_SKIP("step.skip", "跳过"),
    MODE_START_FRESH("mode.start_fresh", "新开任务"),
    MODE_CONTINUE_TASK("mode.continue_task", "继续上一个任务"),
    MODE_START_WITH_CONTEXT("mode.start_with_context", "接着之前的话题新开任务"),
    MODE_REPLACE_TASK("mode.replace_task", "替换当前任务"),
    MODE_SWITCH_AGENT("mode.switch_agent", "切换数字员工"),
    MODE_CANCEL_TASK("mode.cancel_task", "取消当前任务"),
    MODE_REPLY_ONLY("mode.reply_only", "直接回复"),
    MODE_UNCERTAIN("mode.uncertain", "等您确认"),
    MODEL_INTENT("model.intent", "意图识别"),
    MODEL_SKILL_CANDIDATE("model.skill_candidate", "筛选技能"),
    MODEL_PLAN("model.plan", "规划"),
    MODEL_REACT("model.react", "推理"),
    MODEL_GOAL_GATE("model.goal_gate", "校验");

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
        return RuntimeMessageRegistry.resolve(code, RequestLocale.tag(), zhCn);
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
            return text();
        }

        return text().replace("{" + name + "}", Strings.defaultIfBlank(value, ""));
    }

    /**
     * 替换多组具名占位符：{@code name, value, name, value, ...}。
     *
     * @param named 占位名与替换值交替排列
     * @return 文案
     */
    public String text(String... named) {
        String out = text();
        if (named.length < 2) {
            return out;
        }

        for (int i = 0; i + 1 < named.length; i += 2) {
            if (Strings.isNotBlank(named[i])) {
                out = out.replace("{" + named[i] + "}", Strings.defaultIfBlank(named[i + 1], ""));
            }
        }

        return out;
    }
}
