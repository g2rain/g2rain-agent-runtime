package com.g2rain.agent.runtime.engine.tools;


import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

/**
 * 运行时工具上下文与 Spring AI {@link ToolContext} 之间的桥接工具。
 *
 * <p>上下文只在当前工具调用栈中传递，不属于可持久化业务状态；不能把运行时上下文
 * 写入输出、证据或对话。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public class ToolExecutionContexts {

    /**
     * 保存运行时上下文的 Spring ToolContext 属性键。
     */
    public static final String SPRING_ATTR = "g2rain.runtime.tool.context";

    /**
     * 静态桥接工具，禁止实例化。
     */
    private ToolExecutionContexts() {

    }

    /**
     * 把本仓执行上下文包进 Spring ToolContext。
     *
     * @param ctx 本仓工具执行上下文
     * @return 仅包含运行时上下文属性的 Spring ToolContext
     */
    public static ToolContext toSpring(ToolExecutionContext ctx) {
        return new ToolContext(Map.of(SPRING_ATTR, ctx));
    }

    /**
     * 从 Spring ToolContext 取出本仓执行上下文；缺失则抛错。
     *
     * @param context Spring ToolContext
     * @return 已桥接的运行时工具执行上下文
     */
    public static ToolExecutionContext requireSpring(ToolContext context) {
        Object ctx = context.getContext().get(SPRING_ATTR);
        // 键存在且类型正确才算装过桥
        if (ctx instanceof ToolExecutionContext runtime) {
            return runtime;
        }

        throw new IllegalStateException("Missing ToolContext");
    }
}
