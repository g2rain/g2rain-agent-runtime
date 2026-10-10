# 代码生成

仓库使用 `g2rain-crafter`，根目录 `codegen.properties` 是生成配置入口。

```bash
mvn g2rain-crafter:bootstrap
```

生成前确认表结构、包路径和模块归属；生成后必须人工核对 API 暴露范围、权限、状态语义、索引和文档。生成器不能替代任务编排、恢复、工具副作用与 SSE 契约的设计。
