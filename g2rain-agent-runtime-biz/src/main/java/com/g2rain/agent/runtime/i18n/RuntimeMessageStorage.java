package com.g2rain.agent.runtime.i18n;

import com.g2rain.agent.runtime.client.RuntimeMessageClient;
import com.g2rain.common.model.Result;
import com.g2rain.common.syncer.AbstractMessageStorage;
import com.g2rain.common.utils.Collections;
import com.g2rain.infra.dto.I18nMessageSelectDto;
import com.g2rain.infra.enums.I18nMsgUsage;
import com.g2rain.infra.enums.InfraSyncerEnum;
import com.g2rain.infra.vo.I18nMessageVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Agent Runtime 国际化文案的同步存储。
 *
 * <p>启动加载时从 Infra 查询用途为 {@code UI_MESSAGE}、标签为 {@code AGENT_RUNTIME} 的文案；
 * 收到后续同步事件时，按消息编码和 BCP-47 locale 更新或删除进程内注册表中的文案。</p>
 * <p>Infra 查询失败时记录警告，文案查询继续使用 {@link RuntimeMessage} 提供的中文兜底文本。</p>
 *
 * @author alpha
 * @since 2026/11/26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeMessageStorage extends AbstractMessageStorage<String, I18nMessageVo, String> {
    /**
     * 当前服务在 Infra 国际化消息中使用的业务标签。
     */
    static final String TAG = "AGENT_RUNTIME";

    /**
     * 存储键中消息编码与 BCP-47 locale 之间的分隔符。
     */
    static final String KEY_SEPARATOR = "#";

    private final RuntimeMessageClient client;

    /**
     * 返回 Infra 国际化消息同步数据源。
     *
     * @return 国际化消息同步源名称
     */
    @Override
    protected String dataSource() {
        return InfraSyncerEnum.I18N_MESSAGE.name();
    }

    /**
     * 返回同步载荷类型。
     *
     * @return 国际化消息值类型
     */
    @Override
    protected Class<I18nMessageVo> getValueType() {
        return I18nMessageVo.class;
    }

    /**
     * 判断消息是否属于当前服务。
     *
     * @param value 待判断的国际化消息
     * @return 用途为 UI_MESSAGE 且业务标签匹配时返回 {@code true}
     */
    @Override
    protected boolean accepts(I18nMessageVo value) {
        return I18nMsgUsage.UI_MESSAGE.name().equals(value.getMessageUsageCode()) && TAG.equals(value.getTag());
    }

    /**
     * 使用消息编码和 BCP-47 locale 组成缓存键。
     *
     * @param value 国际化消息
     * @return 由消息编码和 locale 拼接而成的缓存键
     */
    @Override
    protected String getKey(I18nMessageVo value) {
        return value.getMessageCode() + KEY_SEPARATOR + locale(value).toLanguageTag();
    }

    /**
     * 从 Infra 加载当前服务的现有文案。查询失败时记录警告，文案查询继续使用中文兜底。
     */
    @Override
    public void load() {
        try {
            I18nMessageSelectDto query = new I18nMessageSelectDto();
            query.setMessageUsageCode(I18nMsgUsage.UI_MESSAGE.name());
            query.setTag(TAG);
            Result<List<I18nMessageVo>> result = client.selectList(query);
            if (Objects.isNull(result) || !result.isSuccess()) {
                log.warn("Could not load {} translations; Chinese fallback will be used", TAG);
                return;
            }

            List<I18nMessageVo> messages = result.getData();
            if (Collections.isEmpty(messages)) {
                log.warn("Could not load {} translations; Chinese fallback will be used", TAG);
                return;
            }

            for (I18nMessageVo message : messages) {
                RuntimeMessageRegistry.put(message.getMessageCode(), locale(message).toLanguageTag(), message.getMessageText());
            }
            log.info("Loaded {} {} translations", messages.size(), TAG);
        } catch (RuntimeException error) {
            log.warn("Could not load {} translations; Chinese fallback will be used", TAG, error);
        }
    }

    /**
     * 将新增文案写入本地注册表。
     *
     * @param key   消息缓存键
     * @param value 新增的国际化消息
     */
    @Override
    protected void create(String key, I18nMessageVo value) {
        put(value);
    }

    /**
     * 使用更新后的文案覆盖本地注册表。
     *
     * @param key   消息缓存键
     * @param value 更新后的国际化消息
     */
    @Override
    protected void update(String key, I18nMessageVo value) {
        put(value);
    }

    /**
     * 从本地注册表删除指定消息编码和 locale 的文案。
     *
     * @param key 消息缓存键
     */
    @Override
    protected void delete(String key) {
        String[] parts = key.split(KEY_SEPARATOR, 2);
        RuntimeMessageRegistry.remove(parts[0], parts[1]);
    }

    /**
     * 根据存储键查询本地文案。
     *
     * @param key 消息缓存键
     * @return 匹配的文案；没有缓存时返回 {@code null}
     */
    @Override
    protected @Nullable String get(String key) {
        String[] parts = key.split(KEY_SEPARATOR, 2);
        return RuntimeMessageRegistry.get(parts[0], parts[1]);
    }

    /**
     * 将消息载荷中的文案写入本地注册表。
     *
     * @param value 国际化消息载荷
     */
    private void put(I18nMessageVo value) {
        RuntimeMessageRegistry.put(value.getMessageCode(), locale(value).toLanguageTag(), value.getMessageText());
    }

    /**
     * 根据语言和地区编码构造文案 locale。
     *
     * @param value 国际化消息载荷
     * @return 对应的 JDK locale
     */
    private Locale locale(I18nMessageVo value) {
        return new Locale.Builder()
            .setLanguage(value.getLanguageCode())
            .setRegion(value.getRegionCode())
            .build();
    }
}
