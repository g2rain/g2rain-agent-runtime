package com.g2rain.agent.runtime.engine.state;


import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.SpaceSpill;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.exception.BusinessException;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 节点空间与 Redis Hash 互转；单字段超限时落 {@code task_node_payload} 并换成溢出占位。
 *
 * @author alpha
 * @since 2026/11/22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpaceCodec {

    /**
     * 属性与嵌套 JSON 编解码。
     */
    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 节点空间超长字段溢出存储。
     */
    private final PayloadRepository payloads;

    /**
     * 空间字段上限配置。
     */
    private final RuntimeProperties properties;

    /**
     * 节点空间 → Redis Hash；单字段超过 {@code space-field-max-bytes} 时落库并换成溢出占位；带 {@link HashCodec#MARK}。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @param space  节点空间
     * @return 可写入 Redis 的 Hash
     */
    public Map<String, String> encode(String taskId, String nodeId, NodeSpace space) {
        Map<String, String> hash = HashCodec.toHash(space);
        int max = properties.getState().getSpaceFieldMaxBytes();
        Map<String, String> out = new LinkedHashMap<>();
        out.put(HashCodec.MARK, "");
        hash.forEach((field, raw) -> out.put(field, spillField(taskId, nodeId, field, raw, max)));
        return out;
    }

    /**
     * 单字段编码：超过 {@code space-field-max-bytes} 时落库并换成溢出占位。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @param field  字段名
     * @param raw    溢出前的 JSON 文本
     * @return 可写入 Redis 的字段值
     */
    public String encodeField(String taskId, String nodeId, String field, String raw) {
        return spillField(taskId, nodeId, field, raw, properties.getState().getSpaceFieldMaxBytes());
    }

    /**
     * Redis Hash → 节点空间：跳过 {@link HashCodec#MARK}，还原溢出占位；只有 MARK 时为 {@code null}。
     *
     * @param hash Redis hGetAll
     * @return 节点空间；只有占位或空为 {@code null}
     */
    public @Nullable NodeSpace decode(Map<String, String> hash) {
        return HashCodec.toObject(expand(hash), NodeSpace.class);
    }

    /**
     * 把溢出占位还原成完整字段 JSON（供 ledger 按溢出前口径登记）。
     *
     * @param hash Redis hGetAll
     * @return 还原后的 Hash
     */
    public Map<String, String> expanded(Map<String, String> hash) {
        return expand(hash);
    }

    /**
     * 把溢出占位还原成完整字段 JSON。
     *
     * @param hash Redis hGetAll
     * @return 还原后的 Hash
     */
    private Map<String, String> expand(Map<String, String> hash) {
        if (Collections.isEmpty(hash)) {
            return hash;
        }
        Map<String, String> out = new LinkedHashMap<>();
        hash.forEach((field, raw) -> out.put(field, expandField(raw)));
        return out;
    }

    /**
     * 超长字段落库并换成 {@link SpaceSpill} JSON。
     *
     * @param taskId 任务 id
     * @param nodeId 节点 id
     * @param field  NodeSpace 字段名
     * @param raw    原始 JSON 文本
     * @param max    允许留在 Redis 的最大字节数
     * @return 原文本或占位 JSON
     */
    private String spillField(String taskId, String nodeId, String field, String raw, int max) {
        if (Strings.isBlank(raw) || raw.getBytes(StandardCharsets.UTF_8).length <= max) {
            return raw;
        }
        String payloadId = RuntimeUtils.uuid();
        try {
            payloads.saveSpaceField(payloadId, taskId, nodeId, field, PrincipalContextHolder.getTraceId(), raw);
        } catch (RuntimeException e) {
            log.error("failed to spillField", e);
            throw new BusinessException(RuntimeErrorCode.STATE_WRITE_FAILED);
        }
        return JSON.obj2str(SpaceSpill.of(payloadId));
    }

    /**
     * 占位则读 OUTPUT；读不到则失败关闭。
     *
     * @param raw Redis 字段文本
     * @return 完整 JSON；非占位则原样返回
     */
    private String expandField(String raw) {
        if (Strings.isBlank(raw) || !raw.trim().startsWith("{")) {
            return raw;
        }
        SpaceSpill spill = JSON.str2obj(raw, SpaceSpill.class);
        if (Objects.isNull(spill) || !spill.valid()) {
            return raw;
        }
        String body = payloads.loadSpaceField(spill.payloadId());
        if (Strings.isBlank(body)) {
            throw new BusinessException(RuntimeErrorCode.STATE_UNAVAILABLE);
        }
        return body;
    }
}
