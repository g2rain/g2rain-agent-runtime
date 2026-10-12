package com.g2rain.agent.runtime.config;


import com.g2rain.agent.runtime.support.OriginResolver;
import com.g2rain.common.utils.Strings;
import com.g2rain.common.web.PrincipalContext;
import com.g2rain.common.web.PrincipalContextHolder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * McpClientConfig：装配 MCP 同步客户端（负载解析、主体头、链路追踪）。
 * <p>
 * Turn（本轮）= 一次用户请求从进入到产出结果的完整处理。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Configuration
public class McpClientConfig {

    /**
     * 放入 MCP 传输上下文的主体头键
     */
    private static final String PRINCIPAL_HEADERS_KEY = "g2rain.principal.headers";

    /**
     * 本应用名，写入 MCP clientInfo
     */
    @Value("${spring.application.name:g2rain-agent-runtime}")
    private String applicationName;

    /**
     * 构建 MCP 同步客户端（listTools 重试在 {@code McpToolCallbackProvider}）。
     *
     * @param client     负载均衡
     * @param tracer     链路追踪
     * @param propagator 追踪头注入
     * @param props      Runtime 配置
     * @return MCP 同步客户端（destroyMethod=close）
     */
    @Bean(destroyMethod = "close")
    McpSyncClient mcpSyncClient(LoadBalancerClient client, Tracer tracer, Propagator propagator, RuntimeProperties props) {
        RuntimeProperties.Mcp mcp = props.getMcp();
        // endpoint 可能是 lb://：先解析出可建连的 baseUrl
        OriginResolver originResolver = OriginResolver.of(client, mcp.getEndpoint());

        // Streamable HTTP 传输：路径、连接超时、每次请求定制（LB / 主体 / 追踪）
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
            .builder(originResolver.getBaseUrl())
            .endpoint(Strings.isBlank(mcp.getPath()) ? "/mcp" : mcp.getPath())
            .connectTimeout(Duration.ofMillis(mcp.getConnectTimeoutMs()))
            .httpRequestCustomizer(requestCustomizer(originResolver, tracer, propagator))
            .build();

        // readTimeoutMs=0 → 近似无限等 MCP 返回；否则按毫秒封顶
        Duration requestTimeout;
        if (mcp.getReadTimeoutMs() == 0) {
            requestTimeout = Duration.ofDays(3650);
        } else {
            requestTimeout = Duration.ofMillis(mcp.getReadTimeoutMs());
        }

        return McpClient.sync(transport)
            .requestTimeout(requestTimeout)
            // clientInfo 标明本 Runtime 身份
            .clientInfo(McpSchema.Implementation.builder(applicationName, "1.0.0").build())
            // 每次调用前把当前线程主体塞进传输上下文，供请求定制读头
            .transportContextProvider(McpClientConfig::currentPrincipalTransportContext)
            .build();
    }

    /**
     * 每次请求的定制：按负载均衡改写 URI，并写入主体头与追踪头。
     *
     * @param resolver   负载均衡地址解析
     * @param tracer     追踪器
     * @param propagator 追踪上下文传播器
     * @return 请求定制器
     */
    private static McpSyncHttpClientRequestCustomizer requestCustomizer(OriginResolver resolver, Tracer tracer, Propagator propagator) {
        return (builder, _, uri, _, context) -> {
            // lb 解析后的真实 URI 与原始不同则改写
            URI resolved = resolver.resolveWithLoadBalancer(uri);
            if (!resolved.equals(uri)) {
                builder.uri(resolved);
            }

            // 主体头（租户/用户等）→ 追踪头
            applyPrincipalHeaders(builder, context);
            applyTracingHeaders(builder, tracer, propagator);
        };
    }

    /**
     * 从传输上下文取出主体头并写入 HTTP 请求。
     *
     * @param builder HTTP 请求构造器
     * @param context MCP 传输上下文
     */
    private static void applyPrincipalHeaders(HttpRequest.Builder builder, McpTransportContext context) {
        Object headers = context.get(PRINCIPAL_HEADERS_KEY);
        // 上下文里没有主体头则跳过
        if (!(headers instanceof Map<?, ?> headerMap)) {
            return;
        }

        // 值是多值 Collection：逐个写成同名 HTTP header
        for (Map.Entry<?, ?> e : headerMap.entrySet()) {
            Object key = e.getKey(), val = e.getValue();
            if (!(key instanceof String k) || !(val instanceof Collection<?> v)) {
                continue;
            }

            v.forEach(value -> builder.header(k, String.valueOf(value)));
        }
    }

    /**
     * 把当前线程主体放进 MCP 传输上下文。
     *
     * @return 带主体头的传输上下文；无主体时为 {@code EMPTY}
     */
    private static McpTransportContext currentPrincipalTransportContext() {
        PrincipalContext principal = PrincipalContextHolder.get();
        if (Objects.isNull(principal)) {
            return McpTransportContext.EMPTY;
        }

        // 只塞 headers，真正写出在 applyPrincipalHeaders
        return McpTransportContext.create(Map.of(
            PRINCIPAL_HEADERS_KEY, principal.getHeaders()
        ));
    }

    /**
     * 把当前 span 上下文注入 HTTP 请求头。
     *
     * @param builder    HTTP 请求构造器
     * @param tracer     追踪器
     * @param propagator 追踪上下文传播器
     */
    private static void applyTracingHeaders(HttpRequest.Builder builder, Tracer tracer, Propagator propagator) {
        Span span = tracer.currentSpan();
        // 无当前 span：不注入，避免造空追踪
        if (Objects.isNull(span)) {
            return;
        }

        // propagator 按约定把头写到 HttpRequest.Builder
        propagator.inject(span.context(), builder, (carrier, key, value) -> {
            if (Objects.isNull(carrier)) {
                return;
            }

            carrier.header(key, value);
        });
    }
}
