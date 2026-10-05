# 本地开发

## 环境

- JDK 25
- Maven 3.9+
- MySQL、Redis、Nacos
- 可访问的 Agent Studio、Model Nexus，以及需要联调的 MCP 服务

默认 profile 为 `nacos`。本地开发可显式选择 `dev`，并使用独立的数据源、缓存与 namespace；不要复用共享或生产凭据。

## 构建与启动

```bash
mvn clean verify
mvn -pl g2rain-agent-runtime-startup -am spring-boot:run -Dspring-boot.run.profiles=dev
```

默认端口为 `8080`，可通过 `SERVER_PORT` 覆盖。健康检查入口为 `GET /actuator/health`；实际 OpenAPI 地址由平台 SpringDoc 配置决定。

## 调试入口

- 启动配置：`g2rain-agent-runtime-startup/src/main/resources`。
- MyBatis Mapper：`g2rain-agent-runtime-biz/src/main/resources/mybatis/mapper`。
- 状态和事件：`docs/STATE_SPEC.md`、`docs/SSE_EVENTS.md`。
- 工具与技能：`docs/SKILL_GUIDE.md`、`docs/CONTRACT_SPEC.md`。
