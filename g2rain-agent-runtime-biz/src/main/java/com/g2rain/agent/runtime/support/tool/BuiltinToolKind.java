package com.g2rain.agent.runtime.support.tool;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 内置工具种类注解：标在 {@code @Tool} 方法上，声明类别与动作。
 *
 * <p>扫描侧读取本注解，把 category/action 挂到 {@link BuiltinToolCallback}。
 * 例：{@code @BuiltinToolKind(category = NODE, action = WRITE)} 表示写节点产出。</p>
 *
 * @author alpha
 * @since 2026/8/29
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BuiltinToolKind {

    /**
     * 工具所属大类（控制流 / 任务记忆 / 环境 / 会话）。
     *
     * @return 类别枚举
     */
    BuiltinToolCategory category();

    /**
     * 工具具体动作（追问、完成、读写任务记忆等）。
     *
     * @return 动作枚举
     */
    BuiltinToolAction action();
}
