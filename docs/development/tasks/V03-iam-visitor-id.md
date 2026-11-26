# V03 · IAM：签发员工令牌时写入访客 ID

## 目标

员工或会员令牌带上「当前密钥对应的同机构访客 ID」。

## 依据

- 设计：`g2rain-agent-runtime/docs/design/visitor-history.md` §3
- 代码：`service.TokenService`：签发入口 `doGenerateToken(String, TokenJWTPayload, boolean)`（登录、刷新、切换机构、会员换票都经过它）、`utils.IamUtils#thumbprint(String)`、`client.LoginTokenClient`

## 范围

只改 `g2rain-server/g2rain-iam`。

1. 在签发入口 `doGenerateToken(String, TokenJWTPayload, boolean)` 写入载荷声明之前设置 `visitorId`（抽成一个私有方法）：
   - 会话类型为 `ANONYMOUS`、或 `organId` 不是正数、或 `clientPublicKey` 为空：置为 `null`。
   - 否则用 `IamUtils.thumbprint(clientPublicKey)` 算指纹，调 Basis `GET /visitor_id`（V02），结果写入 `visitorId`。
   - Basis 返回失败：记 `warn` 日志（不含公钥），`visitorId` 置为 `null`，令牌照常签发。
   - 每次都覆盖，不沿用传入载荷中已有的值（刷新时载荷来自旧令牌）。
2. `LoginTokenClient` 继承 `LoginTokenApi`，升级 Basis api 依赖即可。

## 验收

- 测试：员工登录有访客时带上 ID；无访客为空；匿名令牌不带；刷新、切换机构后按新机构重新查询；Basis 失败时令牌照常签发且不带 ID。
- `mvn clean verify` 通过（依赖 V01、V02）。
