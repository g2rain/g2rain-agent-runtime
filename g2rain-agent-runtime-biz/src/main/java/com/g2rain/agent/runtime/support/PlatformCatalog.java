package com.g2rain.agent.runtime.support;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.Platform;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.BasePrincipal;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 平台字段目录：构造时内省 {@link BasePrincipal}，按 hidden 过滤后供本轮快照与契约校验。
 *
 * <p>getter 表在 Bean 生命周期内只读。{@link #capture} 把当前 principal 写成
 * {@link Platform} 快照；{@link #supports} 判断契约 {@code from: platform.x} 是否合法。
 * 本类不改 principal，也不持久化。</p>
 *
 * @author alpha
 * @since 2026/10/4
 */
@Component
public class PlatformCatalog {

    /**
     * 把 principal 属性写成 JSON 节点的编解码器。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 对外开放字段名到 getter 的映射；构造后只读。
     */
    private final Map<String, Method> getters;

    /**
     * 对外开放字段名集合，与 {@link #getters} 键一致；构造后只读。
     */
    private final Set<String> keys;

    /**
     * 按配置的 hidden 列表内省 {@link BasePrincipal}。
     *
     * @param runtimeProperties 提供 platform.hidden；被列出的属性不会进入目录
     */
    public PlatformCatalog(RuntimeProperties runtimeProperties) {
        Set<String> hidden = new LinkedHashSet<>(runtimeProperties.getPlatform().getHidden());
        Map<String, Method> map = new LinkedHashMap<>();
        try {
            BeanInfo info = Introspector.getBeanInfo(BasePrincipal.class, Object.class);
            for (PropertyDescriptor pd : info.getPropertyDescriptors()) {
                String name = pd.getName();
                Method read = pd.getReadMethod();
                // 无 getter 或配置为 hidden 的属性不进入目录
                if (Strings.isBlank(name) || Objects.isNull(read) || hidden.contains(name)) {
                    continue;
                }

                map.put(name, read);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("PlatformCatalog introspect BasePrincipal failed", ex);
        }

        this.getters = Collections.unmodifiableMap(map);
        this.keys = Collections.unmodifiableSet(new LinkedHashSet<>(map.keySet()));
    }

    /**
     * 把当前 principal 写成平台快照。
     *
     * <p>principal 为空时返回空对象快照。空值属性跳过，不写入 JSON null。</p>
     *
     * @param principal 当前请求身份；可空
     * @return 仅含目录内非空字段的快照
     */
    public Platform capture(@Nullable BasePrincipal principal) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (Objects.isNull(principal)) {
            // 无身份给空快照，不把目录键写成 JSON null
            return new Platform(root);
        }

        for (Map.Entry<String, Method> entry : getters.entrySet()) {
            Object raw;
            try {
                raw = entry.getValue().invoke(principal);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("PlatformCatalog read " + entry.getKey(), ex);
            }

            // 空属性不进快照，避免把 JSON null 当成已注入平台值
            if (Objects.isNull(raw)) {
                continue;
            }

            var node = JSON.obj2node(raw);
            if (JSON.hasValue(node)) {
                root.set(entry.getKey(), node);
            }
        }

        return new Platform(root);
    }

    /**
     * 判断契约 {@code from: platform.x} 是否指向目录内字段。
     *
     * @param key 平台字段名；空白则不支持
     * @return 是否存在且未被 hidden
     */
    public boolean supports(@Nullable String key) {
        return Strings.isNotBlank(key) && getters.containsKey(key.trim());
    }

    /**
     * 返回对外开放字段名，与构造时目录一致。
     *
     * @return 不可变字段名集合
     */
    public Set<String> keys() {
        return keys;
    }
}
