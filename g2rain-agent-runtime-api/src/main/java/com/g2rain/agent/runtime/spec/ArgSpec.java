package com.g2rain.agent.runtime.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单个工具参数的绑定契约。
 *
 * <p>外层 Map 的 key 是参数路径；本对象决定参数从哪个来源取得、何时绑定、是否允许
 * 模型补值，以及缺失时如何向用户追问。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class ArgSpec {

    /**
     * 取值表达式；与 {@link #constant} 互斥。
     */
    private @Nullable String from;

    /**
     * 装载期由 {@code from} 解析；不参与 JSON 序列化。
     */
    @JsonIgnore
    private @Nullable ValueRef valueRef;

    /**
     * 绑定时机；未配置时按 {@link BindingWhen#ALWAYS} 处理。
     */
    private @Nullable BindingWhen when;

    /**
     * 常量；JSON 字段 {@code const}。
     */
    @JsonProperty("const")
    private @Nullable JsonNode constant;

    /**
     * 模型自填允许的来源类型；为空表示不接受模型生成值。
     */
    private List<SourceType> sources = new ArrayList<>();

    /**
     * 是否必填；{@code null} 视为必填。
     */
    private @Nullable Boolean required;

    /**
     * 取值范围；JSON 字段 {@code enum}。
     */
    @JsonProperty("enum")
    private List<String> enumValues = new ArrayList<>();

    /**
     * 缺参动作。
     */
    private @Nullable MissingSpec missing;

    /**
     * 敏感值只存 handle。
     */
    private boolean sensitive;

    /**
     * 追问 / 确认 / 审计展示名。
     */
    private @Nullable String label;

    /**
     * 参数指向的实体类型，与抽取项 {@code entity.type} 同一套命名，如 {@code article}。
     *
     * <p>配置后，「查询结果」来源要求值等于成功证据中该类型对象的标识，「之前任务」来源要求前情中存在该类型、
     * 该标识的对象；未配置时按值在证据或前情中出现即认。</p>
     */
    private @Nullable String entity;

    /**
     * @return 未配置时默认必填
     */
    public boolean isRequired() {
        return Objects.requireNonNullElse(required, true);
    }

    /**
     * 判断是否禁止使用模型生成值。
     *
     * <p>只有明确声明 {@link SourceType#MODEL} 才允许模型补值；用户消息、工具证据和契约
     * 常量等其它来源不受该判断影响。</p>
     *
     * @return 未允许 MODEL 来源时为 {@code true}
     */
    public boolean isForbidSynthetic() {
        return !allows(SourceType.MODEL);
    }

    /**
     * @param type 待判断来源；可空
     * @return 来源是否在 sources 内
     */
    public boolean allows(@Nullable SourceType type) {
        return Objects.nonNull(type) && sources.contains(type);
    }

    /**
     * @return 缺省 ALWAYS
     */
    public BindingWhen whenOrDefault() {
        return Objects.requireNonNullElse(when, BindingWhen.ALWAYS);
    }
}
