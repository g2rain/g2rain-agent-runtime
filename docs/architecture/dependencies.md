# 模块与依赖边界

固定依赖方向如下：

```text
g2rain-agent-runtime-startup -> g2rain-agent-runtime-biz -> g2rain-agent-runtime-api
```

- API 只发布契约和数据模型，不依赖实现模块。
- Biz 实现 API，可依赖持久化、缓存、模型/工具客户端和平台公共库，但不得依赖 Startup。
- Startup 仅承担运行时组装，不向下层暴露业务实现。
- Controller -> Service -> DAO；DAO、Converter 与 API 不得反向依赖 Controller。
- 对 Agent 配置、模型和 MCP 工具的跨服务访问必须通过各自公开 API 或受控客户端完成。

新增模块、同步写接口、跨服务耦合或反向依赖时，必须同步更新 `project.yaml` 和[架构差异](deviations.md)。
