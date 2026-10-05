# 配置说明

配置入口位于 `g2rain-agent-runtime-startup/src/main/resources/application.yml`，默认使用 `nacos` profile；`dev` profile 用于本地联调，二者不应同时激活。

| 配置 | 默认行为 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 端口。 |
| `SPRING_PROFILES_ACTIVE` | `nacos` | Spring Profile。 |
| `NACOS_SERVER_ADDR` | 本地地址 | 注册中心与配置中心地址。 |
| `spring.datasource.*` | Profile/Nacos 提供 | MySQL 数据源。 |
| Redis 相关配置 | Profile/Nacos 提供 | 缓存、会话或运行时协调能力。 |
| Studio/Model/MCP 服务地址 | 受控外部配置 | 运行时依赖的服务发现与调用地址。 |

真实凭据、服务地址和密钥只可通过 Secret、环境变量或受控配置中心提供；文档和提交中不得复制开发默认值。
