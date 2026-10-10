# 模块职责

## `g2rain-agent-runtime-api`

发布 API、DTO、VO、枚举和可复用的运行时契约。该模块不引用 `biz` 或 `startup`。

## `g2rain-agent-runtime-biz`

| 包/区域 | 职责 |
| --- | --- |
| `controller` | 提供任务、会话、消息、检查点和调用记录的 Web 实现。 |
| `service` | 负责业务校验、状态读写与持久化编排。 |
| `domain` | 表达计划、图、执行、工具、状态与恢复语义。 |
| `support` | 封装提示词、MCP、内置工具、序列化和运行时辅助能力。 |
| `client` / `adapter` | 访问 Agent Studio、Model Nexus 等外部公开契约。 |
| `dao` / `converter` | MyBatis 数据访问及 DTO/VO/PO 转换。 |

## `g2rain-agent-runtime-startup`

提供 `com.g2rain.agent.runtime.Application`，装配 Web、虚拟线程、参数解析、环境配置与可运行产物。

## 根工程

根 POM 管理 Java 25、依赖版本、三个 Reactor 模块、Crafter、Flatten 与镜像构建插件；`codegen.properties` 仅用于代码生成。
