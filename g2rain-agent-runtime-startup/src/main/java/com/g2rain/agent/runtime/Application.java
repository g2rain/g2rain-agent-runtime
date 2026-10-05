package com.g2rain.agent.runtime;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Application（Runtime 启动类）= g2rain-agent-runtime 模块的 Spring Boot 入口，扫描并装配对话执行全链路组件。
 *
 * <p>Pipeline：进程启动 → 本类引导 Spring 容器 → 注册 Turn 编排、状态 Store、出站 Client（Model/MCP）与 HTTP Controller。
 *
 * <p>例：{@code java -jar g2rain-agent-runtime-startup.jar} 后监听 HTTP，接收 {@code /conversations/send/stream} 请求。
 *
 * @author alpha
 * @since 2026/8/21
 */
@EnableFeignClients
@SpringBootApplication
public class Application {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
