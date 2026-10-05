package com.g2rain.agent.runtime.config;


import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * ModelClientConfig：启用 RuntimeProperties，并装配带负载均衡的模型 RestClient。
 * <p>
 * Turn（本轮）= 一次用户请求从进入到产出结果的完整处理。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Configuration
@EnableConfigurationProperties(RuntimeProperties.class)
public class ModelClientConfig {

    /**
     * 模型 RestClient.Builder：连接/读超时 + baseUrl（支持 lb://）。
     *
     * @param configurer RestClient 通用配置
     * @param properties Runtime 配置
     * @return 带 @LoadBalanced 的 Builder
     */
    @Bean
    @LoadBalanced
    RestClient.Builder modelRestClientBuilder(RestClientBuilderConfigurer configurer, RuntimeProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        RuntimeProperties.Model cfg = properties.getModel();
        // 短连接超时：尽快失败并重试，躲开注册中心残留的僵尸节点
        requestFactory.setConnectTimeout(Duration.ofMillis(cfg.getConnectTimeoutMs()));
        // 0 = 无限读超时（等模型返回）；>0 才启用有限读超时
        requestFactory.setReadTimeout(cfg.getReadTimeoutMs());
        // baseUrl 可以是 lb:// 服务名或 http(s) 直连
        return configurer.configure(RestClient.builder()).requestFactory(requestFactory).baseUrl(cfg.getEndpoint());
    }

    /**
     * @param modelRestClientBuilder 已配置的 Builder
     * @return 模型 RestClient
     */
    @Bean
    RestClient modelRestClient(RestClient.Builder modelRestClientBuilder) {
        return modelRestClientBuilder.build();
    }
}
