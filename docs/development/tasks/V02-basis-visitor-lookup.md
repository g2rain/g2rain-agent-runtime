# V02 · Basis：按机构与密钥指纹查访客

## 目标

IAM 签发员工令牌时，能查到同一机构下与当前密钥对应的访客 ID；只查不建。

## 依据

- 设计：`g2rain-agent-runtime/docs/design/visitor-history.md`
- 代码：`AnonymousVisitorService` / `AnonymousVisitorServiceImpl`（`resolve`、私有方法 `find`）、`LoginTokenApi` / `LoginTokenController`（`anonymous_token_context` 的写法）

## 范围

只改 `g2rain-server/g2rain-basis`。

1. `AnonymousVisitorService` 新增 `Long findId(Long organId, String thumbprint)`：复用 `find`，查不到返回 `null`；不新建、不更新最后访问时间。
2. `LoginTokenApi` 新增 `GET /visitor_id`，参数 `organId`、`thumbprint`，返回 `Result<Long>`（可为空）；`@Operation` 写明「只查不建，供 IAM 签发员工令牌使用」。
3. 参数校验：`organId` 为正数、`thumbprint` 长度与匿名授权一致（复用 `THUMBPRINT_LENGTH` 的校验方式）。

接口的 Basis 种子（`resource_api` 等）由维护者处理。

## 验收

- 服务测试：有访客返回 ID；没有返回 `null`；不同机构同一指纹不串。
- `mvn clean verify` 通过（依赖 V01 的 common 新版本）。
