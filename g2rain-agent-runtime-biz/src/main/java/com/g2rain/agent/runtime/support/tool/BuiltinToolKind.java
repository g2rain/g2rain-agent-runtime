package com.g2rain.agent.runtime.support.tool;


import com.g2rain.agent.runtime.i18n.RuntimeMessage;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 内置工具种类注解：标在 {@code @Tool} 方法上，声明类别、动作与步骤标题。
 *
 * <p>扫描侧读取本注解，把 category/action/step 挂到 {@link BuiltinToolCallback}。
 * 例：{@code @BuiltinToolKind(category = NODE, action = WRITE, step = RuntimeMessage.STEP_STATE)}。</p>
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

    /**
     * 执行步骤标题文案；缺省为通用的「调用工具」。
     *
     * @return {@link RuntimeMessage} 常量
     */
    RuntimeMessage step() default RuntimeMessage.STEP_TOOL_DEFAULT;
}
