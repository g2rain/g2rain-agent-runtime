package com.g2rain.agent.runtime.engine.flow.entry;


import com.g2rain.agent.runtime.domain.entry.EntryResult;
import com.g2rain.agent.runtime.engine.TurnContext;

/**
 * TurnEntryGuard（本轮入口闸门）：对本轮请求做存在性与续跑协议校验，产出 {@link EntryResult}。
 * <p>
 * 可能结论：拒回、正常受理、切换 Agent、在挂起节点上强制续跑。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface TurnEntryGuard {

    /**
     * 校验本轮入口并返回分流结论；必要时把会话/任务写入 {@link TurnContext}。
     *
     * @param context 本轮上下文（至少已带 {@link com.g2rain.agent.runtime.engine.TurnRequest}）
     * @return 入口结论（accept / reject / switchAgent / continueSuspended）
     */
    EntryResult guard(TurnContext context);
}
