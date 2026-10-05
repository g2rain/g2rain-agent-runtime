package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.model.nexus.dto.ChatCompletionRequest;

/**
 * PromptAssembler（提示组装）：把本轮交互历史、可见工具表与节点现场拼成一次模型请求。
 *
 * <p>只负责组请求，不调用模型、不改写任务记忆。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface PromptAssembler {

    /**
     * 组装聊天补全请求。
     *
     * @param request 组装入参
     * @return 发给模型网关的请求
     */
    ChatCompletionRequest assemble(PromptAssembleRequest request);
}
