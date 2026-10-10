# 构建与部署

```bash
mvn clean verify
./build.sh <tag>
```

部署前确认：

- 镜像版本与当前提交、Maven 版本一致。
- Nacos namespace、配置分组、服务发现、MySQL、Redis 与依赖服务均已按环境隔离。
- 网关路由、服务身份、授权和健康检查已配置。
- Agent Studio、Model Nexus、MCP Registry 的契约版本与可用性已验证。
- 发布策略包含失败回滚；有副作用的执行任务需评估在滚动发布期间的恢复策略。
