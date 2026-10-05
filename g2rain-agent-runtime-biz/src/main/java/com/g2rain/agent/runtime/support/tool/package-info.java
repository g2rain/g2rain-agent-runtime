/**
 * 内置工具支撑：类别、动作注解、回调装饰与 Spring AI 回调供应。
 *
 * <p>把带 {@code @Tool} 的方法登记为可被模型调用的回调，并挂上
 * {@link com.g2rain.agent.runtime.support.tool.BuiltinToolCategory} 与
 * {@link com.g2rain.agent.runtime.support.tool.BuiltinToolAction}。
 * 例：节点产出标 NODE+WRITE；追问/完成标 CONTROL+ASK/FINISH。
 * 本包不执行业务编排，只提供工具身份与委托调用。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.support.tool;

import org.jspecify.annotations.NullMarked;
