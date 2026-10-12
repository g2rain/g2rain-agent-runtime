package com.g2rain.agent.runtime.support.trace;

import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * 把会话、任务、节点 ID 写入 MDC，使作用域内的所有日志自动带上定位信息；关闭时恢复为开启前的值。
 *
 * <p>MDC 绑定在线程上，作用域须以 try-with-resources 使用，保证正常结束和异常时都会恢复。
 * 跨虚拟线程的传递由框架上下文传播完成。只在两个编排边界开启：本轮入口用 {@link #turn}，
 * 节点执行用 {@link #node}；任务在本轮中途确定时用 {@link #bindTask} 写入，由本轮作用域统一清理。</p>
 *
 * <pre>{@code
 * try (TraceScope ignored = TraceScope.turn(conversationId)) {
 *     return execute(ctx);
 * }
 * }</pre>
 *
 * @author alpha
 * @since 2026/10/11
 */
public final class TraceScope implements AutoCloseable {

    /**
     * 会话 ID 的 MDC 键。
     */
    public static final String CONVERSATION_ID = "conversationId";

    /**
     * 任务 ID 的 MDC 键。
     */
    public static final String TASK_ID = "taskId";

    /**
     * 节点 ID 的 MDC 键。
     */
    public static final String NODE_ID = "nodeId";

    /**
     * 开启前各键的值；{@code null} 表示开启前不存在。
     */
    private final Map<String, @Nullable String> previous = new HashMap<>();

    /**
     * 记录各键开启前的值。
     *
     * @param keys 本作用域会改写的 MDC 键
     */
    private TraceScope(String... keys) {
        for (String key : keys) {
            previous.put(key, MDC.get(key));
        }
    }

    /**
     * 开启本轮作用域：写入会话 ID，并清掉残留的任务、节点 ID。
     *
     * @param conversationId 会话 ID；可空
     * @return 作用域，关闭时三个键都恢复为开启前的值
     */
    public static TraceScope turn(@Nullable String conversationId) {
        var scope = new TraceScope(CONVERSATION_ID, TASK_ID, NODE_ID);
        put(CONVERSATION_ID, conversationId);
        MDC.remove(TASK_ID);
        MDC.remove(NODE_ID);
        return scope;
    }

    /**
     * 开启节点作用域：写入节点 ID。
     *
     * @param nodeId 节点 ID；可空
     * @return 作用域，关闭时节点 ID 恢复为开启前的值
     */
    public static TraceScope node(@Nullable String nodeId) {
        TraceScope scope = new TraceScope(NODE_ID);
        put(NODE_ID, nodeId);
        return scope;
    }

    /**
     * 写入当前任务 ID；空值清除。由本轮作用域在结束时统一恢复。
     *
     * @param taskId 任务 ID；可空
     */
    public static void bindTask(@Nullable String taskId) {
        put(TASK_ID, taskId);
    }

    /**
     * 恢复各键为开启前的值。
     */
    @Override
    public void close() {
        previous.forEach(TraceScope::put);
    }

    /**
     * 写入 MDC；空值时移除该键。
     *
     * @param key   MDC 键
     * @param value 值；可空
     */
    private static void put(String key, @Nullable String value) {
        if (Strings.isBlank(value)) {
            MDC.remove(key);
            return;
        }

        MDC.put(key, value);
    }
}
