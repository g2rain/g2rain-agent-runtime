package com.g2rain.agent.runtime.enums;


import com.g2rain.common.exception.ErrorCode;

/**
 * Agent Runtime 领域错误码枚举，承载本模块特有的业务异常语义。
 *
 * <p><b>Pipeline 位置</b>：贯穿 Turn 全链路（entry → react → finalize），由工具守卫、
 * 任务记忆写入、控流工具与出站依赖在各阶段抛出。
 *
 * <p><b>读写方</b>：biz 层引擎在异常路径写入；API 模块对外暴露码表供调用方识别与国际化。
 *
 * <p>例如按名调度工具未命中 → {@link #TOOL_NOT_FOUND}；model-nexus 超时 → {@link #DEPENDENCY_UNAVAILABLE}。
 *
 * @author alpha
 * @since 2026/8/21
 */
public enum RuntimeErrorCode implements ErrorCode {

    /**
     * 工具不存在（回调表未命中）。
     */
    TOOL_NOT_FOUND("agent_runtime.40401", "工具不存在：{0:toolName}"),

    MCP_TOOL_ERROR("agent_runtime.40003", "{0}"),

    TOOL_BUSINESS_FAILED("agent_runtime.40004", "{0}"),

    MCP_INVALID_PARAMS("agent_runtime.40014", "{0}"),

    MCP_RPC_ERROR("agent_runtime.40015", "{0}"),

    TOOL_TIMEOUT("agent_runtime.40016", "{0}"),

    TOOL_UNREACHABLE("agent_runtime.40017", "{0}"),

    TOOL_IO_ERROR("agent_runtime.40018", "{0}"),

    TOOL_NON_JSON("agent_runtime.40019", "{0}"),

    /**
     * 调用层未知异常兜底（写工具记 UNKNOWN）。
     */
    TOOL_ERROR("agent_runtime.40029", "{0}"),

    ARG_JSON_INVALID("agent_runtime.40020", "{0}"),

    ARG_UNRESOLVED_REF("agent_runtime.40021", "{0}"),

    ARG_REJECTED("agent_runtime.40032", "{0}"),

    PARAM_PROVENANCE_FAIL("agent_runtime.40022", "{0}"),

    PARAM_PROVENANCE_CALL_TOOL("agent_runtime.40023", "{0}"),

    PARAM_PROVENANCE_ASK_USER("agent_runtime.40024", "{0}"),

    PARAM_PROVENANCE_REJECTED("agent_runtime.40025", "{0}"),

    FINGERPRINT_TRIP("agent_runtime.40026", "{0}"),

    CATALOG_DENIED("agent_runtime.40027", "{0}"),

    STATE_KEY_RESERVED("agent_runtime.40028", "禁止写入保留键：{0:key}"),

    /**
     * 写操作结果待确认 / 进行中，勿重复调用。
     */
    WRITE_PENDING_VERIFY("agent_runtime.40030", "该写操作结果待确认，请等待回查，不要重复调用"),

    /**
     * 写操作已有进行中的同幂等键调用。
     */
    WRITE_IN_FLIGHT("agent_runtime.40031", "该写操作进行中，请勿重复调用"),

    /**
     * 控流工具缺少执行上下文。
     *
     * <p>{@code builtin_node_finish} / {@code follow_up} 等必须经 {@code ToolExecutionContext} 调用。
     */
    TOOL_EXECUTION_CONTEXT_REQUIRED("agent_runtime.40005", "控流工具缺少执行上下文：{0:toolName}"),

    /**
     * 节点已不在运行中，拒绝再次控流。
     */
    NODE_NOT_RUNNING("agent_runtime.40901", "节点不在运行中，拒绝控流：{0:nodeStatus}"),

    /**
     * 内置工具入参不合法。
     */
    BUILTIN_BAD_ARGS("agent_runtime.40007", "{0:message}"),

    /**
     * 续跑动作不在挂起允许列表中。
     */
    RESUME_ACTION_NOT_ALLOWED("agent_runtime.40902", "不允许的续跑动作：{0:action}"),

    /**
     * 打开任务缺少 taskId。
     */
    OPEN_TASK_TASK_ID_REQUIRED("agent_runtime.40008", "openTask 缺少 taskId"),

    /**
     * 打开任务元数据版本冲突（CAS 失败）。
     */
    OPEN_TASK_VERSION_CONFLICT("agent_runtime.40903", "openTask 版本冲突：{0:taskId}"),

    /**
     * 依赖不可用。
     *
     * <p>出站依赖超时、重试耗尽或服务不可达（如 model-nexus）。
     */
    DEPENDENCY_UNAVAILABLE("agent_runtime.50003", "依赖不可用：{0:dependency}"),

    CONVERSATION_BUSY("agent_runtime.40910", "会话忙碌"),

    CONVERSATION_NOT_FOUND("agent_runtime.40410", "会话不存在：{0:conversationId}"),

    CONVERSATION_FORBIDDEN("agent_runtime.40310", "无权访问该会话"),

    STATE_FENCED("agent_runtime.40911", "状态栅栏冲突"),

    STATE_WRITE_FAILED("agent_runtime.50010", "状态写入失败"),

    STATE_UNAVAILABLE("agent_runtime.50011", "状态存储不可用"),

    APPROVAL_EXPIRED("agent_runtime.40912", "确认已过期"),

    ARGS_HASH_MISMATCH("agent_runtime.40913", "已批准参数与确认时不一致"),

    INVALID_DEFAULT("agent_runtime.42201", "非法默认值：{0}"),

    UNKNOWN_FIELD("agent_runtime.42202", "未知字段：{0}"),

    MISSING_REFERENCE("agent_runtime.42203", "引用缺失：{0}"),

    ILLEGAL_SOURCE("agent_runtime.42204", "非法来源：{0}"),

    INVALID_EXPRESSION("agent_runtime.42205", "非法表达式：{0}"),

    WRITE_CONTRACT_INCOMPLETE("agent_runtime.42206", "写工具契约不完整：{0}"),

    VERIFY_NOT_READONLY("agent_runtime.42207", "verify 工具必须只读：{0}"),

    SKILL_NOT_FOUND("agent_runtime.40411", "技能不存在：{0:skillKey}"),

    TARGET_TOOL_INVALID("agent_runtime.42208", "目标工具非法：{0}"),

    DONE_WHEN_INVALID("agent_runtime.42209", "doneWhen 非法：{0}"),

    TOOL_NOT_DECLARED("agent_runtime.42210", "工具未在契约中声明：{0:toolName}"),

    MISSING_INPUT("agent_runtime.42211", "缺少输入：{0}"),

    PROVIDER_UNAVAILABLE("agent_runtime.42212", "provider 不可用：{0}"),

    OUTPUT_TYPE_INVALID("agent_runtime.42213", "输出类型非法：{0}"),

    OUTPUT_FIELDS_INVALID("agent_runtime.42214", "输出字段非法：{0}"),

    OUTPUT_MIN_ITEMS("agent_runtime.42215", "输出条数不足：{0}"),

    OUTPUT_REQUIRED_MISSING("agent_runtime.42216", "缺少必填输出：{0}"),

    SHARE_WRITE_FORBIDDEN("agent_runtime.42217", "禁止写入共享值：{0}"),

    SENSITIVE_VALUE_REJECTED("agent_runtime.42218", "敏感值被拒绝：{0}"),

    ENTITY_SCHEMA_INVALID("agent_runtime.42219", "实体 schema 非法：{0}"),

    ENTITY_ID_MISSING("agent_runtime.42220", "实体 id 缺失"),

    APPROVAL_REQUIRED("agent_runtime.42221", "需要用户确认：{0}"),

    VERIFY_ARG_INVALID("agent_runtime.42222", "verify 参数非法：{0}"),

    SOURCE_REJECT("agent_runtime.42223", "来源校验失败：{0}"),

    ARG_CONFLICT("agent_runtime.42224", "参数冲突：{0}"),

    ARG_CONDITION_NOT_MET("agent_runtime.42225", "参数条件不满足：{0}"),

    MISSING_REQUIRED_ARG("agent_runtime.42226", "缺少必填参数：{0}"),

    ARG_ENUM_INVALID("agent_runtime.42227", "参数枚举非法：{0}"),

    MISSING_TOOL("agent_runtime.42228", "补数工具缺失：{0}"),

    STALE_PRIOR_REJECTED("agent_runtime.42229", "过期前情被拒绝"),

    BUSINESS_FAILURE("agent_runtime.42230", "业务失败：{0}"),

    EXTRACT_PATH_INVALID("agent_runtime.42231", "extracts.path 非法：{0}"),

    DONE_WHEN_FALSE("agent_runtime.42232", "完成条件未满足：{0}"),

    TARGET_NOT_SUCCEEDED("agent_runtime.42233", "目标工具未成功：{0}"),

    SHARED_VALUE_MISSING("agent_runtime.42234", "共享值缺失：{0}"),

    EVIDENCE_PATH_INVALID("agent_runtime.42235", "证据路径非法：{0}"),

    PLATFORM_FIELD_FORBIDDEN("agent_runtime.42236", "禁止使用平台字段：{0}"),

    DELEGATE_FORBIDDEN("agent_runtime.42237", "禁止委派：{0}"),

    DELEGATE_BROKEN("agent_runtime.42238", "委派链损坏：{0}"),

    DELEGATE_REPEATED("agent_runtime.42239", "重复委派被拒绝：{0}"),

    ASK_USER("agent_runtime.42240", "需向用户追问：{0}"),

    CALL_TOOL("agent_runtime.42241", "需先调用补数工具：{0}"),

    FAIL("agent_runtime.42242", "缺参失败：{0}"),

    OUTPUT_PATH_INVALID("agent_runtime.42243", "输出 path 非法：{0:path}"),

    OUTPUT_WRITE_FORBIDDEN("agent_runtime.42244", "禁止写入节点正式产出：{0:path}"),

    APPROVAL_NOT_GATED("agent_runtime.42245", "须先调用写工具 [{0:toolName}]，被拦下后再申请确认");

    /**
     * 错误码字符串（如 {@code agent_runtime.40401}）；由 {@link #code()} 读出供全局异常处理器消费。
     */
    private final String code;

    /**
     * 消息模板，支持 {@code {0:name}} 占位；由 {@link #messageTemplate()} 读出供国际化渲染。
     */
    private final String messageTemplate;

    RuntimeErrorCode(String code, String messageTemplate) {
        this.code = code;
        this.messageTemplate = messageTemplate;
    }

    /**
     * 返回稳定错误码字符串。
     *
     * @return 如 {@code agent_runtime.40401}
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回带占位符的消息模板。
     *
     * @return 模板文本，占位形如 {@code {0:toolName}}
     */
    @Override
    public String messageTemplate() {
        return messageTemplate;
    }
}
