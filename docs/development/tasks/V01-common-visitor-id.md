# V01 · common：身份上下文增加访客 ID

## 目标

令牌与身份上下文可以携带「当前浏览器的访客 ID」，经网关以请求头转发到业务服务。

## 依据

- 设计：`g2rain-agent-runtime/docs/design/visitor-history.md`
- 代码：`g2rain-common` 的 `web.BasePrincipal`、`web.TokenJWTPayload`（`entryId` 的写法）、`web.PrincipalHeaders`、`web.PrincipalContext`（`getValue` / `setValue` 的分支）、`web.PrincipalContextHolder`（`getUserId` 的写法）

## 范围

只改 `g2rain-server/g2rain-common`。

1. `BasePrincipal` 新增 `visitorId`（Long）。注释：员工或会员会话中，与当前客户端密钥对应的同机构访客 ID，由 IAM 签发时写入；`ANONYMOUS` 会话不携带（其 `userId` 即访客 ID）。
2. `PrincipalHeaders` 新增 `VISITOR_ID("X-VISITOR-ID", "x-visitor-id")`，放在 `MEMBER_ID` 之后。
3. `PrincipalContext` 的 `getValue`、`setValue` 补上 `VISITOR_ID` 分支，写法同 `USER_ID`。
4. `PrincipalContextHolder` 新增 `getVisitorId()`，写法同 `getUserId()`。
5. `TokenJWTPayload` 继承 `BasePrincipal`，自动带上字段；类注释的字段列表补一行。

## 验收

- `PrincipalContext` 的请求头读写测试覆盖 `VISITOR_ID`；`TokenJWTPayload` 序列化测试覆盖 `visitorId`。
- `mvn clean verify` 通过；版本号按现有方式递增，交付说明写明新版本号。
