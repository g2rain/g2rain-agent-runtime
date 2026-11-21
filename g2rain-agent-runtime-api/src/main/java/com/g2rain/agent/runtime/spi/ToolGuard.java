package com.g2rain.agent.runtime.spi;

/**
 * 调用前附加检查：在业务工具真正调用前拦下不符合前置条件的调用。
 *
 * <p>结论为拒绝则本次调用被拦下；放行时可附带警告，由 Runtime 展示给用户并告知模型。
 * 没有任何实现时 Runtime 行为不变。</p>
 *
 * <p>多个实现时按实现类上的 {@code @Order}（或实现 {@code Ordered}）决定先后，
 * Runtime 按此顺序依次检查、首个拒绝即止；未标注的排在最后。注入方不做额外排序。</p>
 *
 * @author alpha
 * @since 2026/11/17
 */
public interface ToolGuard {

    /**
     * 检查本次调用是否允许继续。
     *
     * @param call 只读调用视图
     * @return 检查结论
     */
    GuardVerdict check(CallView call);
}
