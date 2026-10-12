package com.g2rain.agent.runtime.enums;


import com.g2rain.common.utils.Strings;

import java.util.Objects;

/**
 * 给模型的纠偏/观察文案：码 + zh-CN 默认句。用户可见文案见 {@link RuntimeMessage}。
 *
 * <p>工具观测的非成功文案分两类：原因（说明发生了什么）与下一步（{@code NEXT_*}，说明模型该做什么），
 * 由观测统一拼成"原因。下一步：…"。例：{@code ARG_MISSING_REQUIRED} + {@code NEXT_FILL_ARGS}。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
public enum ModelHint {

    ARG_JSON_INVALID("model.arg.json_invalid", "入参 JSON 非法：{detail}"),
    ARG_NOT_OBJECT("model.arg.not_object", "入参须为 JSON 对象"),
    ARG_MISSING_REQUIRED("model.arg.missing_required", "缺少必填参数 [{fields}]"),

    PARAM_CALL_TOOL("model.param.call_tool", "参数 [{path}] 须来自工具 [{tool}] 的查询结果"),
    PARAM_ASK_USER("model.param.ask_user", "参数 [{fields}] 需要用户提供或确认"),
    PARAM_ARRAY_LEN_MISMATCH("model.param.array_len_mismatch", "参数数组有 {target} 条，与查询结果的 {working} 条不一致"),
    PARAM_MISSING_TRUSTED("model.param.missing_trusted", "缺少可信来源参数 [{key}]"),
    PARAM_COLLECTION_CALL_TOOL("model.param.collection_call_tool", "集合字段 [{path}] 须来自工具 [{tool}] 的查询结果"),
    PARAM_NEED_EVIDENCE("model.param.need_evidence", "参数 [{path}] 须来自工具 [{tool}] 的查询结果，不能由模型填写"),
    PARAM_NEED_EVIDENCE_NO_TOOL("model.param.need_evidence_no_tool", "参数 [{path}] 须来自工具查询结果，不能由模型填写"),
    PARAM_NO_SYNTHETIC("model.param.no_synthetic", "参数 [{path}] 不能由模型填写"),

    USER_CONFIRMED("model.user.confirmed", "用户已确认执行 [{tool}]"),
    USER_VERIFIED("model.user.verified", "用户确认写操作 [{tool}] 已生效"),
    USER_UNVERIFIED("model.user.unverified", "用户确认写操作 [{tool}] 未生效"),
    USER_ANSWERED("model.user.answered", "用户回答了追问「{question}」（对应参数 {fields}）：{answer}"),
    USER_ANSWERED_PLAIN("model.user.answered_plain", "用户回答了追问「{question}」：{answer}"),

    WRITE_NEED_APPROVAL("model.write.need_approval", "该写操作须经用户确认"),
    WRITE_REUSED("model.write.reused", "已复用历史写证据"),

    FINGERPRINT_TRIP("model.fingerprint.trip", "已连续用相同参数调用 [{tool}]，本节点已终止"),

    CATALOG_DENIED("model.catalog.denied", "工具 [{tool}] 不在本节点可用工具中"),

    BUSINESS_EMPTY_BODY("model.business.empty_body", "业务失败：响应体为空"),
    BUSINESS_FAILED("model.business.failed", "业务失败：{message}"),
    BUSINESS_FAILED_CODE("model.business.failed_code", "业务失败 [{code}]：{message}"),

    NEXT_FIX_ARGS("model.next.fix_args", "按工具参数说明修正入参后再调用"),
    NEXT_USE_TOOLS("model.next.use_tools", "上一轮没有发起工具调用。请直接调用工具：完成用 builtin_node_finish，追问用 builtin_node_follow_up，失败用 builtin_node_fail；不要在正文中写伪工具调用。"),
    FOLLOW_UP_NO_BASIS("model.follow_up.no_basis", "没有待补参数，也不在查询为空或业务失败之后"),
    FOLLOW_UP_FIELDS_INVALID("model.follow_up.fields_invalid", "参数 [{fields}] 既不能由用户提供，也没有可供选择的查询结果"),
    NEXT_FOLLOW_UP("model.next.follow_up", "写操作直接调用写工具，确认由平台审批完成；只有缺少只能由用户提供的参数时才追问，并在 expectedFields 填写工具参数名"),
    DELEGATE_CATALOG("model.delegate.catalog", "需要其他技能先完成一个独立的前置步骤时调用 builtin_node_delegate；可委派的技能：{skills}"),
    NEXT_USE_ANSWER("model.next.use_answer", "按用户的回答继续，不要重复追问"),
    NEXT_FILL_ARGS("model.next.fill_args", "补齐后再调用；无法补齐时调用 builtin_node_follow_up 询问用户，或调用 builtin_node_fail 说明原因"),
    NEXT_CALL_TOOL("model.next.call_tool", "先调用 [{tool}] 查到该值，再用查到的值调用本工具"),
    NEXT_ASK_USER("model.next.ask_user", "调用 builtin_node_follow_up 向用户询问"),
    NEXT_ASK_OR_FAIL("model.next.ask_or_fail", "调用 builtin_node_follow_up 询问用户，或调用 builtin_node_fail 说明原因"),
    NEXT_ALIGN_ROWS("model.next.align_rows", "按查询结果逐条组织数组后再调用"),
    NEXT_FAIL("model.next.fail", "调用 builtin_node_fail 说明原因"),
    NEXT_STOPPED("model.next.stopped", "本节点已终止，无需再调用工具"),
    NEXT_CATALOG("model.next.catalog", "只调用 tools 中提供的工具"),
    NEXT_APPROVAL("model.next.approval", "调用 builtin_node_approval 申请批准：toolName 填 {tool}，summary 用一句话说明将要做的事"),
    NEXT_APPROVED_FAILED("model.next.approved_failed", "用户已批准的写操作执行失败：用 builtin_node_follow_up 向用户说明失败原因并询问如何处理（如修改后重试或放弃）；用户不再继续时调用 builtin_node_fail 说明原因；不要自行改用其它写操作补救"),
    NEXT_WAIT_WRITE("model.next.wait_write", "不要重复调用该写工具；先用查询工具确认结果，无法确认时调用 builtin_node_fail 说明原因"),
    NEXT_BUSINESS("model.next.business", "根据失败原因修正参数后再调用；无法修正时调用 builtin_node_follow_up 询问用户，或调用 builtin_node_fail 说明原因"),
    NEXT_RETRY("model.next.retry", "可再调用一次；仍失败则调用 builtin_node_fail 说明原因"),
    NEXT_TIMEOUT("model.next.timeout", "写操作不要重复调用，先用查询工具确认是否已生效；读操作可再调用一次；仍失败则调用 builtin_node_fail 说明原因"),
    NEXT_OUTPUT_KEY("model.next.output_key", "key 只能取契约声明的产出：{keys}"),
    NEXT_OUTPUT_SOURCE_TYPE("model.next.output_source_type", "sourceType 取以下之一：{types}"),
    NEXT_OUTPUT_SOURCE_REF("model.next.output_source_ref", "sourceRef 填来源对象 ID（工具结果的证据 ID 或用户消息 ID）"),
    NEXT_OUTPUT_VALUE("model.next.output_value", "value 须与来源中 sourcePath 指向的内容完全一致，不能裁剪或改写；可先用 builtin_evidence_get 查看证据原文"),
    NEXT_OUTPUT_CONST("model.next.output_const", "value 须与契约常量一致"),
    NEXT_OUTPUT_OPTIONAL("model.next.output_optional", "该产出非必填，也可以不写，直接调用 builtin_node_finish"),
    NEXT_STATE_KEY("model.next.state_key", "换一个未被保留的便签键"),
    NEXT_EVIDENCE_ID("model.next.evidence_id", "evidenceId 填工具结果中返回的证据 ID"),
    NEXT_DELEGATE("model.next.delegate", "skillKey 只能取可委派的技能：{skills}；没有合适的技能时调用 builtin_node_fail 说明原因"),
    NEXT_NO_DELEGATE("model.next.no_delegate", "不要再委派；直接完成本节点，或调用 builtin_node_fail 说明原因"),
    NEXT_CALL_WRITE("model.next.call_write", "先直接调用写工具，被拦下要求确认后再调用 builtin_node_approval"),
    NEXT_APPROVAL_TOOL("model.next.approval_tool", "toolName 改填 {tool} 后重新申请"),

    FINISH_REJECTED("model.finish.rejected", "FINISH 被拒绝"),
    FINISH_MISSING_OUTPUT("model.finish.missing_output", "FINISH 缺少 output。须补齐产出后再 finish，或 follow_up / fail。"),
    FINISH_OPEN_EVIDENCE("model.finish.open_evidence", "存在未收口证据，不能 finish。"),
    FINISH_NEED_TARGET("model.finish.need_target", "须先成功调用 {tool} 后再 finish。"),
    FINISH_NEED_BUSINESS("model.finish.need_business", "须至少成功调用一次业务工具后再 finish。"),
    FINISH_NO_NODE("model.finish.no_node", "缺少节点视图，无法校验 outputs。"),
    FINISH_OUTPUT_REQUIRED("model.finish.output_required", "必填 outputs.{key} 尚未写入"),
    FINISH_OUTPUT_SOURCE_TYPE("model.finish.output_source_type", "outputs.{key} 来源类型不被允许"),
    FINISH_OUTPUT_CONST("model.finish.output_const", "outputs.{key} 与契约常量不一致"),
    FINISH_OUTPUT_SOURCE("model.finish.output_source", "outputs.{key} 来源校验未通过"),

    SKIP_NODE_EXITED("model.skip.node_exited", "本波因节点已退出未执行"),
    SKIP_FINISH_REJECTED("model.skip.finish_rejected", "本波因 finish 被拒未执行"),
    SKIP_PREVIOUS_FAILED("model.skip.previous_failed", "本波因前序工具未成功未执行"),

    PHASE_OUTPUTS_DONE("model.phase.outputs_done", "必填 outputs 已写齐"),
    PHASE_OUTPUTS_MISSING("model.phase.outputs_missing", "必填 outputs 尚未写入：{keys}"),
    PHASE_TARGET_PENDING("model.phase.target_pending", "完成目标 {tool} 尚未成功调用"),
    PHASE_TARGET_DONE("model.phase.target_done", "完成目标 {tool} 已成功调用"),
    PHASE_WRITE_DONE("model.phase.write_done", "已完成写操作 {tool}；用户诉求如还有未完成的写操作就继续，否则调用 builtin_node_finish");

    private final String code;
    private final String zhCn;

    ModelHint(String code, String zhCn) {
        this.code = code;
        this.zhCn = zhCn;
    }

    /**
     * 文案码。
     */
    public String code() {
        return code;
    }

    /**
     * zh-CN 默认文案。
     */
    public String text() {
        return zhCn;
    }

    /**
     * 替换具名占位符 {@code {name}}。
     */
    public String text(String name, String value) {
        if (Strings.isBlank(name)) {
            return zhCn;
        }
        return zhCn.replace("{" + name + "}", Strings.defaultIfBlank(value, ""));
    }

    /**
     * 多组具名占位：{@code name, value, name, value, ...}。
     */
    public String text(String... named) {
        String out = zhCn;
        if (Objects.isNull(named) || named.length < 2) {
            return out;
        }
        for (int i = 0; i + 1 < named.length; i += 2) {
            String name = named[i];
            if (Strings.isBlank(name)) {
                continue;
            }
            out = out.replace("{" + name + "}", Strings.defaultIfBlank(named[i + 1], ""));
        }
        return out;
    }
}
