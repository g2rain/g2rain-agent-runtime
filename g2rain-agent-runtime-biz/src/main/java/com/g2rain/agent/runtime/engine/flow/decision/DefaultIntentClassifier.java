package com.g2rain.agent.runtime.engine.flow.decision;


import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.prompt.IntentPrompt;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.support.ChatCompletionRequests;
import com.g2rain.model.nexus.support.OpenAiChatClientConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 使用一次模型调用生成候选意图结果的分类器。
 *
 * <p>system 为 {@link IntentPrompt}，user 为整份 {@link IntentRequest} JSON。
 * 无响应、无法解析或调用异常时返回 {@code null}，由调度器降级。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultIntentClassifier implements IntentClassifier {

    /**
     * 将请求序列化为模型输入并解析结构化输出。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 发起本轮意图分类的 chat 调用。
     */
    private final ModelGateway modelGateway;

    /**
     * 模型空正文时的最多调用次数（含首次）。
     */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * 完成一次意图分类。
     *
     * <p>方法按“组装请求、调用模型、提取 JSON、反序列化、候选校验”的顺序处理结果；模型
     * 无响应、输出无法解析或调用异常时返回 {@code null}，由上层规则决定降级动作。</p>
     *
     * @param request 分类输入（运行态布尔、摘抄、候选 Agent、用户原文等）
     * @return 完整意图结果；失败或空响应返回 {@code null}
     */
    @Override
    public @Nullable IntentResult classify(IntentRequest request) {
        try {
            String user = JSON.obj2str(request);
            String content = reply(user);
            // 模型偶发首 token 即结束（空正文），重试后仍空才降级
            for (int attempt = 2; attempt <= MAX_ATTEMPTS && Strings.isBlank(content); attempt++) {
                log.warn("[IntentClassifier] empty reply, retry attempt={}", attempt);
                content = reply(user);
            }

            // 没有可解析正文时不猜测模式，让调度器走安全降级
            if (Strings.isBlank(content)) {
                return null;
            }

            String json = RuntimeUtils.extractObject(content);
            IntentResult parsed = JSON.str2obj(json, IntentResult.class);
            if (Objects.isNull(parsed)) {
                return null;
            }

            // 用候选列表校验 agentId，避免模型编造不存在的人选
            return parsed.complete(request.agentCandidates());
        } catch (RuntimeException ex) {
            // 分类异常一律 null，不把模型错误泄漏成推进模式
            return null;
        }
    }

    /**
     * 发起一次分类调用。
     *
     * @param user 序列化后的分类请求
     * @return 模型正文；无响应为 {@code null}
     */
    private @Nullable String reply(String user) {
        ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(
            ChatCompletionRequests.of().system(IntentPrompt.TEXT).user(user).build()
        ));
        return Objects.isNull(assistant) ? null : assistant.getContent();
    }
}
