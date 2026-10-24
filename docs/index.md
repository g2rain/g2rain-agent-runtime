# g2rain-agent-runtime 文档

本目录是 Agent Runtime 的项目级事实来源，覆盖运行时边界、执行状态、开发交付和运维约束。现有专项规范保留在本目录根部，并由本索引统一导航。

项目按 g2rain `java-domain-service 1.0.0` Profile 进行接入准备；公共规则由中央 Profile 管理，本仓库维护运行时的领域事实和[架构差异](architecture/deviations.md)。

## 架构

- [架构总览](architecture/overview.md)
- [模块职责](architecture/modules.md)
- [依赖边界](architecture/dependencies.md)
- [运行流程](architecture/runtime-flows.md)
- [架构差异与接入项](architecture/deviations.md)
- [整体架构评估](architecture/reviews/architecture-review.md)
- [Java 类架构](architecture/java-class-architecture.md)
- [与 pi-py 的架构对比](architecture/comparisons/pi-py-comparison.md)
- [状态模型](STATE_SPEC.md) 与 [状态图](STATE_MODEL.svg)
- [SSE 事件](SSE_EVENTS.md)
- [运行时契约](CONTRACT_SPEC.md)
- [技能指南](SKILL_GUIDE.md)
- [意图路由与选人](INTENT_ROUTING.md)
- [链路轨迹日志](TRACE_LOGGING.md)
- [工具结果与进展判定](TOOL_OBSERVATION.md)
- [执行过程时间线与失败原因透传](design/progress-and-failure.md)（已实施，待联调）

## 开发与交付

- [需求模板](requirements/README.md)
- [平台与 pi-py 客户接受度评估](requirements/research/platform-pi-py-customer-acceptance.md)
- [MCP 编排与 Skill 兼容性提升需求](requirements/mcp-orchestration-compatibility_chatgpt.md)
- [本地开发](development/local-development.md)
- [代码规范](development/code-conventions.md)
- [API 约定](development/api-conventions.md)
- [数据库约定](development/database-conventions.md)
- [测试策略](development/testing.md)
- [完成定义](development/definition-of-done.md)
- [代码生成](development/code-generation.md)
- [架构决策记录](decisions/README.md)

## 安全与运维

- [安全边界](security/security-boundaries.md)
- [配置说明](operations/configuration.md)
- [构建与部署](operations/deployment.md)
- [可观测性](operations/observability.md)
- [故障排查](operations/troubleshooting.md)
- [已知问题](KNOWN_ISSUES.md)
- [社区、贡献与许可证](community.md)

修改任务状态、API、配置、工具协议、事件或部署方式时，必须在同一变更中同步相应文档。
