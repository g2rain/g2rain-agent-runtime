package com.g2rain.agent.runtime.engine.flow.react;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.support.prompt.ReactPrompt;
import com.g2rain.agent.runtime.support.prompt.ReplyLanguage;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.model.nexus.dto.ChatCompletionRequest;
import com.g2rain.model.nexus.schema.ChatMessage;
import com.g2rain.model.nexus.schema.ChatTool;
import com.g2rain.model.nexus.support.ChatCompletionRequests;
import com.g2rain.model.nexus.support.ChatMessages;
import com.g2rain.model.nexus.support.ChatTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * DefaultPromptAssembler：把当前节点现场组装成 ReAct 模型请求。
 *
 * <p>system = {@link ReactPrompt}；user = 投影 record 序列化后的带标签 JSON；再拼 history + tools[]。
 *
 * @author alpha
 * @since 2026/8/21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultPromptAssembler implements PromptAssembler {

    /**
     * 将投影块序列化为带标签 JSON。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 缺参时的空 object schema。
     */
    private static final String EMPTY_OBJ_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    /**
     * 投影块工厂。
     */
    private final ReactProjectionFactory projectionFactory;

    /**
     * 运行时配置（投影总长度）。
     */
    private final RuntimeProperties runtimeProperties;

    /**
     * 组装本轮模型请求：system + 投影 user + history + tools。
     *
     * @param request 组装入参
     * @return 聊天补全请求
     */
    @Override
    public ChatCompletionRequest assemble(PromptAssembleRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessages.system(ReactPrompt.TEXT.formatted(ReplyLanguage.tag())));
        String user = projectUser(request);
        messages.add(ChatMessages.user(user));

        if (Collections.isNotEmpty(request.historyMessages())) {
            messages.addAll(request.historyMessages());
        }

        List<ChatTool> chatTools = toChatTools(request.toolCallbacks());
        log.debug("[PromptAssembler] assemble nodeId={} historySize={} toolCount={} userChars={}",
            request.nodeId(),
            request.historyMessages().size(),
            chatTools.size(),
            user.length()
        );

        return ChatCompletionRequests.of().messages(messages).tools(chatTools).build();
    }

    /**
     * 将投影 record 序列化为带标签 JSON；超长丢低优先级整块。
     *
     * @param request 组装入参
     * @return user 文本
     */
    private String projectUser(PromptAssembleRequest request) {
        int max = runtimeProperties.getReact().getWorkspaceSliceMaxChars();
        List<ReactProjection.TaggedBlock> blocks = new ArrayList<>(projectionFactory.blocks(request));
        while (Collections.isNotEmpty(blocks) && render(blocks).length() > max) {
            // 超长时从队尾丢掉低优先级整块，避免半截 JSON
            blocks.removeLast();
        }
        return render(blocks);
    }

    /**
     * {@code <tag>{json}</tag>} 拼接；json 来自 record 序列化。
     *
     * @param blocks 投影块
     * @return user 文本
     */
    private static String render(List<ReactProjection.TaggedBlock> blocks) {
        StringBuilder text = new StringBuilder();
        blocks.forEach(block -> {
            text.append('<').append(block.tag()).append('>');
            text.append(JSON.obj2str(block.payload()));
            text.append("</").append(block.tag()).append(">\n");
        });
        return text.toString();
    }

    /**
     * 工具回调转模型侧 tools[] 定义。
     *
     * @param toolCallbacks 本节点工具
     * @return Chat tools
     */
    private static List<ChatTool> toChatTools(List<ToolCallback> toolCallbacks) {
        if (Collections.isEmpty(toolCallbacks)) {
            return List.of();
        }

        return toolCallbacks.stream()
            .map(ToolCallback::getToolDefinition)
            .map(def -> ChatTools.function(def.name())
                .description(def.description())
                .parameters(parseSchema(def.inputSchema()))
                .build()
            ).toList();
    }

    /**
     * 解析工具入参 schema；缺省或非法时兜底，避免整次组装失败。
     *
     * @param inputSchema 工具入参 schema 字符串
     * @return 解析结果或原文
     */
    private static Object parseSchema(String inputSchema) {
        if (Strings.isBlank(inputSchema)) {
            return JSON.str2obj(EMPTY_OBJ_SCHEMA, JsonNode.class);
        }

        try {
            return JSON.str2obj(inputSchema, JsonNode.class);
        } catch (RuntimeException ex) {
            log.error("failed to parse schema", ex);
            // 非法 schema 原样交给模型，不让整次组装失败
            return inputSchema;
        }
    }
}
