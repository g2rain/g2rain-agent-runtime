package com.g2rain.agent.runtime.engine.model;


import com.g2rain.model.nexus.dto.ChatCompletionRequest;
import com.g2rain.model.nexus.dto.EmbeddingsRequest;
import com.g2rain.model.nexus.vo.ChatCompletionResponse;
import com.g2rain.model.nexus.vo.EmbeddingsResponse;

/**
 * ModelGateway（模型网关）：对大模型服务的统一出站口。
 * <p>
 * 屏蔽具体 HTTP 路径与重试细节，只暴露聊天补全与向量化两类能力。
 * 例：规划/ReAct 调 {@link #chat}；需要向量检索时调 {@link #embed}。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
public interface ModelGateway {

    /**
     * 发起一次 Chat Completions 请求。
     *
     * @param request 聊天补全请求（模型、消息、可选 tools 等）
     * @return 聊天补全响应
     */
    ChatCompletionResponse chat(ChatCompletionRequest request);

    /**
     * 发起一次 Embeddings 向量化请求。
     *
     * @param request 向量化请求（模型、待嵌入文本等）
     * @return 向量化响应
     */
    EmbeddingsResponse embed(EmbeddingsRequest request);
}
