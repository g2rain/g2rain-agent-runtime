package com.g2rain.agent.runtime.domain.hydrate;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 从工具响应抽取记录：path 取数组或对象、fields 投影列、key 写入证据语义键。
 *
 * <p>抽取结果进入 {@link com.g2rain.agent.runtime.domain.state.task.EvidenceHead} 的计数与实体候选，
 * 完成条件 {@code evidence.<key> exists} 依赖本规格。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
@Getter
@Setter
@NoArgsConstructor
public class ExtractSpec {

    /**
     * 证据语义键，对应完成条件里的 {@code evidence.<key>}。
     */
    private String key;

    /**
     * 响应体内的取值路径，指向记录或记录数组。
     */
    private String path;

    /**
     * 每条记录要保留的字段名；空列表表示保留整条。
     */
    private List<String> fields = new ArrayList<>();

    /**
     * 可选实体声明；有则抽取后生成 {@link com.g2rain.agent.runtime.domain.state.task.EntitySeed}。
     */
    private @Nullable EntitySpec entity;
}
