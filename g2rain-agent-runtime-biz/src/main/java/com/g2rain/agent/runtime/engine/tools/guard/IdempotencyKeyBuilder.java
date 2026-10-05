package com.g2rain.agent.runtime.engine.tools.guard;


import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 写工具幂等键构造器。
 *
 * <p>有声明路径时按 {@code toolName|path=value} 拼出原文再算 SHA-256；否则使用工具名和
 * 完整规范化参数的摘要。返回值始终为定长十六进制摘要，适合直接写入数据库唯一键。</p>
 * <p>声明路径为空、参数中找不到任何声明字段或契约为空时，自动回退到完整参数摘要；因此
 * 调用方无需为读工具或缺少业务键的工具额外设计字符串拼接规则。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public final class IdempotencyKeyBuilder {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    /**
     * 静态构造器，禁止实例化。
     */
    private IdempotencyKeyBuilder() {

    }

    /**
     * 根据工具名、契约声明和实际参数生成有效幂等键。
     *
     * <p>声明 {@code ["invoiceId"]} 时，只要参数中存在该字段，键原文就是工具名和
     * {@code invoiceId} 的规范化值；没有可用声明字段时，退回完整参数摘要。</p>
     *
     * <pre>{@code
     * String key = IdempotencyKeyBuilder.effectiveKey(
     *         "invoice.create", toolSpec, args);
     * }</pre>
     *
     * @param toolName 工具名；为空时不把工具名加入回退原文
     * @param contract 工具契约；为空或未声明路径时使用完整参数摘要
     * @param args     本次工具调用的规范化参数
     * @return 定长幂等键
     */
    public static String effectiveKey(String toolName, @Nullable ToolSpec contract, JsonNode args) {
        String tool = Strings.defaultIfBlank(toolName, "");
        // 没有可用业务键时，使用工具名和完整参数摘要，保证同工具同参数得到稳定结果。
        String material = Strings.isBlank(tool) ? RuntimeUtils.argsHash(args) : tool + "|" + RuntimeUtils.argsHash(args);
        String fallback = RuntimeUtils.sha256Hex(material.getBytes(StandardCharsets.UTF_8));
        if (Objects.isNull(contract) || Collections.isEmpty(contract.getIdempotencyKey())) {
            return fallback;
        }

        List<String> paths = new ArrayList<>();
        for (String path : contract.getIdempotencyKey()) {
            if (Strings.isNotBlank(path)) {
                paths.add(path.trim());
            }
        }

        if (paths.isEmpty()) {
            return fallback;
        }

        paths.sort(Comparator.naturalOrder());

        StringBuilder sb = new StringBuilder(Strings.defaultIfBlank(toolName, ""));
        boolean any = false;
        for (String path : paths) {
            JsonNode at = JSON.lookupNode(args, path);
            if (Objects.isNull(at) || at.isNull() || at.isMissingNode()) {
                continue;
            }
            sb.append('|').append(path).append('=').append(JSON.obj2str(at));
            any = true;
        }

        if (!any) {
            return fallback;
        }

        return RuntimeUtils.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
