package com.g2rain.agent.runtime.enums;


import com.g2rain.common.utils.Strings;

import java.util.Objects;

/**
 * 给模型的纠偏/观察文案：码 + zh-CN 默认句。用户可见文案见 {@link RuntimeMessage}。
 *
 * @author alpha
 * @since 2026/10/4
 */
public enum ModelHint {

    ARG_JSON_INVALID("model.arg.json_invalid", "入参 JSON 非法：{detail}"),
    ARG_UNRESOLVED_REF("model.arg.unresolved_ref", "入参含未解析引用 [{refs}]。请填实值后再调"),
    ARG_NOT_OBJECT("model.arg.not_object", "入参须为 JSON 对象。请按工具 schema 重交，或 follow_up / node_fail。"),
    ARG_MISSING_REQUIRED("model.arg.missing_required", "缺少必填参数 [{fields}]。请补齐后再调，或 follow_up / node_fail。"),

    PARAM_CALL_TOOL("model.param.call_tool", "须先调用工具 [{tool}] 补齐参数"),
    PARAM_ASK_USER("model.param.ask_user", "参数需用户确认：{fields}。请 follow_up 追问。"),
    PARAM_ARRAY_LEN_MISMATCH("model.param.array_len_mismatch", "参数数组长度与 outputs 集合不一致: target={target} working={working}"),
    PARAM_MISSING_TRUSTED("model.param.missing_trusted", "缺少可信来源参数: {key}"),
    PARAM_COLLECTION_CALL_TOOL("model.param.collection_call_tool", "集合字段 [{path}] 须先调工具 [{tool}]"),
    PARAM_NEED_EVIDENCE("model.param.need_evidence", "参数 [{path}] 必须来自工具 Evidence，须先调用 [{tool}]。"),
    PARAM_NEED_EVIDENCE_NO_TOOL("model.param.need_evidence_no_tool", "参数 [{path}] 必须来自工具 Evidence。"),
    PARAM_NO_SYNTHETIC("model.param.no_synthetic", "参数 [{path}] 不可仅由模型编造，请 follow_up 确认或写入 outputs。"),

    REPAIR_ASK_USER("model.repair.ask_user", "须 follow_up 补齐：{fields}"),
    REPAIR_CALL_TOOL("model.repair.call_tool", "须先调用工具 [{tool}]"),
    REPAIR_PLATFORM_MISSING("model.repair.platform_missing", "平台上下文缺失，请 node_fail"),
    REPAIR_PROVENANCE_REJECT("model.repair.provenance_reject", "参数溯源被拒，请 follow_up 或改写 outputs 后再调"),
    REPAIR_MAX_ROUNDS("model.repair.max_rounds", "补参义务超过 {rounds} 轮，请 follow_up 说明或 node_fail。"),
    REPAIR_ASK_ONLY("model.repair.ask_only", "本轮仅允许 follow_up / output_set / node_fail。"),
    REPAIR_CALL_OR_FOLLOW_UP("model.repair.call_or_follow_up", "须先调用 [{tool}] 或 follow_up。"),

    WRITE_NEED_APPROVAL("model.write.need_approval", "写工具须先确认"),
    WRITE_REUSED("model.write.reused", "已复用历史写证据"),

    FINGERPRINT_TRIP("model.fingerprint.trip", "连续相同参数调用 [{tool}]，已熔断"),

    BUSINESS_EMPTY_BODY("model.business.empty_body", "业务失败：响应体为空"),

    FINISH_REJECTED("model.finish.rejected", "FINISH 被拒绝"),
    FINISH_MISSING_OUTPUT("model.finish.missing_output", "FINISH 缺少 output。须补齐产出后再 finish，或 follow_up / fail。"),
    FINISH_OPEN_EVIDENCE("model.finish.open_evidence", "存在未收口证据，不能 finish。"),
    FINISH_REPAIR_PENDING("model.finish.repair_pending", "存在补参义务，不能 finish。"),
    FINISH_NEED_TARGET("model.finish.need_target", "须先成功调用 {tool} 后再 finish。"),
    FINISH_NEED_BUSINESS("model.finish.need_business", "须至少成功调用一次业务工具后再 finish。"),
    FINISH_NO_NODE("model.finish.no_node", "缺少节点视图，无法校验 outputs。"),
    FINISH_OUTPUT_REQUIRED("model.finish.output_required", "必填 outputs.{key} 尚未写入"),
    FINISH_OUTPUT_SOURCE_TYPE("model.finish.output_source_type", "outputs.{key} 来源类型不被允许"),
    FINISH_OUTPUT_CONST("model.finish.output_const", "outputs.{key} 与契约常量不一致"),
    FINISH_OUTPUT_SOURCE("model.finish.output_source", "outputs.{key} 来源校验未通过"),
    FINISH_OR_FAILED("model.finish.or_failed", "OR 均未满足：{reasons}"),
    FINISH_DONE_WHEN_NEED_TARGET("model.finish.done_when_need_target", "doneWhen 需要 targetTool。"),
    FINISH_EVIDENCE_TOOL("model.finish.evidence_tool", "evidence.{tool} 尚未成功"),
    FINISH_TOOL_NOT_EMPTY("model.finish.tool_not_empty", "{tool} 结果非空或未成功"),
    FINISH_DONE_WHEN_UNKNOWN("model.finish.done_when_unknown", "无法解析 doneWhen：{raw}"),
    FINISH_OUTPUT_FIELD_BLANK("model.finish.output_field_blank", "outputs 字段名为空"),
    FINISH_OUTPUT_MISSING("model.finish.output_missing", "outputs.{key} 尚未写入"),
    FINISH_OUTPUT_COLLECTION_BLANK("model.finish.output_collection_blank", "outputs 集合名为空"),
    FINISH_OUTPUT_PREDICATE("model.finish.output_predicate", "outputs 集合谓词不完整"),
    FINISH_EVIDENCE_MISSING("model.finish.evidence_missing", "evidence.{key} 不存在"),
    FINISH_OUTPUT_NOT_COLLECTION("model.finish.output_not_collection", "outputs.{key} 不是集合"),
    FINISH_OUTPUT_LENGTH("model.finish.output_length", "outputs.{key} 长度 {size} < {required}"),
    FINISH_OUTPUT_EMPTY("model.finish.output_empty", "outputs.{key} 为空"),
    FINISH_OUTPUT_ITEM_NOT_OBJECT("model.finish.output_item_not_object", "outputs.{key}[{index}] 不是对象"),
    FINISH_OUTPUT_ITEM_FIELD("model.finish.output_item_field", "outputs.{key}[{index}].{field} 尚未写入");

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
