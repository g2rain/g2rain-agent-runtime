package com.g2rain.agent.runtime.config;

import com.g2rain.agent.runtime.client.RuntimeMessageClient;
import com.g2rain.agent.runtime.i18n.RuntimeMessageRegistry;
import com.g2rain.common.model.Result;
import com.g2rain.infra.dto.I18nMessageSelectDto;
import com.g2rain.infra.enums.I18nMsgUsage;
import com.g2rain.infra.vo.I18nMessageVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Agent Runtime 国际化文案启动加载器。
 *
 * <p>应用启动时从 Infra 查询用途为 {@code UI_MESSAGE}、标签为 {@code AGENT_RUNTIME} 的文案，
 * 并将消息编码、语言和文案写入 {@link RuntimeMessageRegistry} 内存缓存。
 * 如果 Infra 不可用或没有返回有效数据，RuntimeMessage 会使用枚举中定义的中文兜底文案。</p>
 *
 * <p>文案仅在启动时加载一次，运行期间查询文案不会发起网络请求。</p>
 *
 * @author alpha
 * @since 2026/10/31
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeMessages implements ApplicationRunner {

    /**
     * 用于从 Infra 查询国际化文案的 Feign 客户端。
     */
    private final RuntimeMessageClient client;

    /**
     * 在 Spring Boot 启动阶段加载 Runtime 国际化文案。
     *
     * <p>加载失败时记录警告并继续启动，后续文案查询使用 {@code RuntimeMessage} 中的中文兜底文本。</p>
     *
     * @param args Spring Boot 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            loadMessages();
        } catch (RuntimeException error) {
            log.warn("Could not load AGENT_RUNTIME translations; Chinese fallback will be used", error);
        }
    }

    /**
     * 从 Infra 查询全部 {@code AGENT_RUNTIME} 文案，并写入进程内注册表。
     *
     * <p>查询结果无效时不修改注册表，由 {@code RuntimeMessage} 提供兜底文案。</p>
     */
    private void loadMessages() {
        I18nMessageSelectDto query = new I18nMessageSelectDto();
        query.setMessageUsageCode(I18nMsgUsage.UI_MESSAGE.name());
        query.setTag("AGENT_RUNTIME");
        Result<List<I18nMessageVo>> result = client.selectList(query);
        if (Objects.isNull(result) || !result.isSuccess()) {
            log.warn("Could not load AGENT_RUNTIME translations; Chinese fallback will be used");
            return;
        }

        List<I18nMessageVo> data = result.getData();
        for (I18nMessageVo message : data) {
            String locale = new Locale.Builder()
                .setLanguage(message.getLanguageCode())
                .setRegion(message.getRegionCode())
                .build()
                .toLanguageTag();

            RuntimeMessageRegistry.put(
                message.getMessageCode(),
                locale,
                message.getMessageText()
            );
        }

        log.info("Loaded {} AGENT_RUNTIME translations", data.size());
    }
}
