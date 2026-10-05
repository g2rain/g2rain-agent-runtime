package com.g2rain.agent.runtime.config;


import org.apache.coyote.AbstractProtocol;
import lombok.NonNull;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

/**
 * VirtualThreadConfigurer（虚拟线程配置）= 为 Tomcat Connector 配置 Java 虚拟线程执行器，提升高并发 HTTP 吞吐。
 *
 * <p>Pipeline：Spring 启动 → 本类定制 {@link TomcatServletWebServerFactory} → 每个 HTTP 请求在独立虚拟线程处理，替代固定大小线程池。
 *
 * <p>例：并发 1000 路 SSE 长连接时，虚拟线程阻塞等待模型返回不会占满平台线程。
 *
 * @author alpha
 * @since 2026/8/21
 */
@Configuration
public class VirtualThreadConfigurer {

    /**
     * 为 Tomcat Connector 配置虚拟线程执行器。
     *
     * <p>使用 Java 25 虚拟线程处理 HTTP 请求，提高高并发吞吐量。</p>
     *
     * @return 自定义 WebServerFactoryCustomizer，用于设置 Connector 执行器
     */
    @Bean
    public WebServerFactoryCustomizer<@NonNull TomcatServletWebServerFactory> virtualThreadCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            if (!(connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol)) {
                return;
            }

            protocol.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
        });
    }
}
