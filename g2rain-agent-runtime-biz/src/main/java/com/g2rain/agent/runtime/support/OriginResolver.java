package com.g2rain.agent.runtime.support;


import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.common.utils.Asserts;
import com.g2rain.common.utils.Strings;
import lombok.Getter;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Locale;

/**
 * 把配置里的 endpoint 解析成可出站的 baseUrl，需要时做负载均衡改写。
 *
 * <p>支持 {@code http(s)://} 直连和 {@code lb://} 服务发现。
 * {@code lb://} 先用占位 http origin 满足只认 http(s) 的客户端，真正发请求前由
 * {@link #resolveWithLoadBalancer} 换成选中实例。本类不发起业务请求。</p>
 *
 * <pre>{@code
 * OriginResolver origin = OriginResolver.of(client, "lb://g2rain-mcp/api");
 * URI live = origin.resolveWithLoadBalancer(URI.create(origin.getBaseUrl() + "/tools"));
 * }</pre>
 *
 * @author alpha
 * @since 2026/8/21
 */
public final class OriginResolver {

    /**
     * 负载均衡 URI 协议名：endpoint 以 {@code lb://} 开头时启用服务发现。
     */
    private static final String LB_SCHEME = "lb";

    /**
     * Spring Cloud 负载均衡客户端：按 serviceId 挑选存活实例。
     */
    private final LoadBalancerClient client;

    /**
     * 是否为 lb 模式；为 true 时 {@link #resolveWithLoadBalancer} 会改写 URI。
     */
    private final boolean lb;

    /**
     * lb 模式下的服务名（来自 {@code lb://} 的 host）；直连模式为 {@code null}。
     */
    private final String serviceId;

    /**
     * 规范化后的基址 URL（无末尾斜杠）。
     * <p>
     * lb 模式为占位地址（如 {@code http://g2rain-mcp/api}）；直连模式为配置的 http(s) 地址。
     */
    @Getter
    private final String baseUrl;

    /**
     * 内部构造：由 {@link #of} 按协议分支填充字段。
     *
     * @param client    负载均衡客户端
     * @param baseUrl   规范化基址
     * @param lb        是否 lb 模式
     * @param serviceId lb 服务名；直连时为 {@code null}
     */
    private OriginResolver(LoadBalancerClient client, String baseUrl, boolean lb, String serviceId) {
        this.client = client;
        this.lb = lb;
        this.baseUrl = baseUrl;
        this.serviceId = serviceId;
    }

    /**
     * 根据 endpoint 字符串工厂创建解析器。
     * <p>
     * 例：{@code of(client, "lb://order-mcp/tools")} → lb=true、serviceId=order-mcp；
     * {@code of(client, "https://host/v1/")} → lb=false。
     *
     * @param client   负载均衡客户端；lb 与直连构造都需要
     * @param endpoint 配置的服务地址（http(s) 或 lb）
     * @return 解析完成的 OriginResolver
     * @throws com.g2rain.common.exception.BusinessException 地址空白、协议非法或 lb 缺少服务名
     */
    public static OriginResolver of(LoadBalancerClient client, String endpoint) {
        Asserts.notNull(client, RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "LoadBalancerClient");
        Asserts.notBlank(endpoint, RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "endpoint");
        endpoint = endpoint.trim();
        URI configured = URI.create(endpoint);
        String scheme = configured.getScheme();
        Asserts.notBlank(scheme, RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "endpoint.scheme");
        String normalized = scheme.toLowerCase(Locale.ROOT);

        if (LB_SCHEME.equals(normalized)) {
            String serviceId = configured.getHost();
            Asserts.notBlank(serviceId, RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "endpoint.host");
            String path = configured.getRawPath();
            // Http 客户端不认 lb://：先占位 http origin，出站时再改写成实例地址
            String origin = "http://" + serviceId;
            if (Strings.isNotBlank(path) && !"/".equals(path)) {
                origin = origin + path;
            }

            return new OriginResolver(client, normalizeUrl(origin), true, serviceId);
        }

        // 直连只认 http(s)，其它协议拒绝以免发出无法出站的 origin
        boolean httpOrHttps = "http".equals(normalized) || "https".equals(normalized);
        Asserts.isTrue(httpOrHttps, RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "endpoint.scheme");
        return new OriginResolver(client, normalizeUrl(endpoint), false, null);
    }

    /**
     * 在 lb 模式下把请求 URI 的 scheme/host/port 换成当前选中实例；直连模式原样返回。
     * <p>
     * 例：占位 {@code http://order-mcp/tools/x} → 选中实例后变成 {@code http://10.0.0.8:8080/tools/x}。
     *
     * @param uri 待改写的请求 URI（通常基于 {@link #baseUrl} 拼出）
     * @return lb 模式下改写后的 URI；直连则为入参本身
     */
    public URI resolveWithLoadBalancer(URI uri) {
        // 直连模式不改写 URI，避免误走服务发现
        if (!this.lb) {
            return uri;
        }

        Asserts.notNull(uri, RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, "requestUri");
        ServiceInstance instance = this.client.choose(this.serviceId);
        Asserts.notNull(instance, RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, this.serviceId);

        URI origin = instance.getUri();
        // 只替换 scheme/host/port，保留原 path 与 query
        return UriComponentsBuilder.fromUri(uri)
            .scheme(origin.getScheme())
            .host(origin.getHost())
            .port(origin.getPort())
            .build(true).toUri();
    }

    /**
     * 去掉首尾空白与末尾多余斜杠，得到稳定的基址字符串。
     *
     * @param url 原始 URL；空白则原样返回
     * @return 规范化后的 URL
     */
    private static String normalizeUrl(String url) {
        // 空地址原样返回，避免把空白规范化成非法 origin
        if (Strings.isBlank(url)) {
            return url;
        }

        url = url.trim();
        // 去掉末尾斜杠，保证基址拼接 path 时不会出现双斜杠
        if (url.endsWith("/")) {
            url = url.replaceAll("/+$", "");
        }

        return url;
    }
}
