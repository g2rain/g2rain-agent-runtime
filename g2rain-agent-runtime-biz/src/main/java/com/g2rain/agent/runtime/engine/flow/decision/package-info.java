/**
 * Decision（本轮决策）包：决定推进模式并绑定执行 Agent。
 *
 * <p>含调度器、意图分类、Agent 解析与目录。入口硬协议（强制续跑或换 Agent）跳过模型分类；其余按会话场景
 * 调用意图分类，再校正为场景允许的模式，模型无有效结论时按场景兜底。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@NullMarked
package com.g2rain.agent.runtime.engine.flow.decision;

import org.jspecify.annotations.NullMarked;
