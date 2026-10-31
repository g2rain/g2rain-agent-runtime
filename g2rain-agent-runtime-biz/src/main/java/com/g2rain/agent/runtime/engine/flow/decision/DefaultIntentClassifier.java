package com.g2rain.agent.runtime.engine.flow.decision;


import com.g2rain.agent.runtime.domain.decision.IntentResult;
import com.g2rain.agent.runtime.engine.model.ModelGateway;
import com.g2rain.agent.runtime.engine.model.ModelUse;
import com.g2rain.agent.runtime.support.FailureCategory;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.agent.runtime.support.prompt.IntentPrompt;
import com.g2rain.agent.runtime.support.prompt.ReplyLanguage;
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
 * 使用一次模型调用生成意图结论的分类器。
 *
 * <p>system 为 {@link IntentPrompt#SYSTEM}，user 由 {@link IntentPrompt#USER} 填入场景、上下文 JSON 与用户原文。
 * 无响应、无法解析或调用异常时记录原因并返回 {@code null}，由调度器按场景兜底。</p>
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
     * 日志中模型原文的最大预览长度。
     */
    private static final int PREVIEW_LENGTH = 200;

    /**
     * 发起本轮意图分类的 chat 调用。
     */
    private final ModelGateway modelGateway;

    /**
     * 完成一次意图分类。
     *
     * <p>按“渲染提示词、调用模型、提取 JSON、反序列化、候选校验”的顺序处理；模型无响应、
     * 输出无法识别或调用异常时记录原因并返回 {@code null}。</p>
     *
     * @param request 本轮分类输入
     * @return 规范化后的意图结论；失败返回 {@code null}
     */
    @Override
    public @Nullable IntentResult classify(IntentRequest request) {
        try {
            String user = IntentPrompt.USER.formatted(
                request.scene().name(), JSON.obj2str(request), Objects.toString(request.userInput(), "")
            );

            ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(ModelUse.INTENT, request.model(),
                ChatCompletionRequests.of().system(IntentPrompt.SYSTEM.formatted(ReplyLanguage.tag())).user(user).build()
            ));

            String content = Objects.isNull(assistant) ? null : assistant.getContent();
            if (Strings.isBlank(content)) {
                log.warn("[IntentClassifier] empty reply, scene={}", request.scene());
                return null;
            }

            IntentResult parsed = JSON.str2obj(RuntimeUtils.extractObject(content), IntentResult.class);
            if (Objects.isNull(parsed) || Objects.isNull(parsed.getMode())) {
                log.warn("[IntentClassifier] unrecognized reply, scene={}, content={}", request.scene(), RuntimeUtils.abbreviate(content, PREVIEW_LENGTH));
                return null;
            }

            return parsed.complete(request.agentCandidates());
        } catch (RuntimeException ex) {
            // 模型额度、密钥等致命失败不降级，交给收口按分类回复
            FailureCategory.throwIfFatal(ex);
            log.warn("[IntentClassifier] classify failed, scene={}", request.scene(), ex);
            return null;
        }
    }
}
