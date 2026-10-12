package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.exception.BusinessException;

/**
 * 内置工具拒绝本次调用：调用违反工具规则，模型按提示调整即可避免。
 *
 * <p>与普通业务异常不同，拒绝必须带上下一步，工具调用层据此生成 {@code REJECTED} 观测；
 * 内置工具抛出的其它业务异常一律按执行失败处理。例：
 * {@code throw new ToolRejectedException(RuntimeErrorCode.APPROVAL_NOT_GATED, ModelHint.NEXT_CALL_WRITE.text(), toolName)}。</p>
 *
 * @author alpha
 * @since 2026/10/11
 */
public class ToolRejectedException extends BusinessException {

    /**
     * 拒绝对应的错误码。
     */
    private final RuntimeErrorCode error;

    /**
     * 模型下一步。
     */
    private final String next;

    /**
     * 创建拒绝异常。
     *
     * @param error 错误码，其文案模板即拒绝原因
     * @param next  模型下一步
     * @param args  错误码文案的占位参数
     */
    public ToolRejectedException(RuntimeErrorCode error, String next, Object... args) {
        super(error, args);
        this.error = error;
        this.next = next;
    }

    /**
     * @return 拒绝对应的错误码
     */
    public RuntimeErrorCode error() {
        return error;
    }

    /**
     * @return 模型下一步
     */
    public String next() {
        return next;
    }
}
