# V04 · 网关：转发访客 ID

## 目标

网关把令牌中的 `visitorId` 放进上下文，转发给业务服务；匿名令牌不得携带。

## 依据

- 设计：`g2rain-agent-runtime/docs/design/visitor-history.md` §3
- 代码（webmvc 与 webflux 各一份，两边保持一致）：`filters.GatewayTokenAuthFilter`（`context.setEntryId(...)` 的位置，以及 `ANONYMOUS` 会话的失败关闭校验）、`filters.PrincipalForwardFilter`（转发前移除全部 `PrincipalHeaders` 再写入可信值）

## 范围

改 `g2rain-server/g2rain-gateway-webmvc` 与 `g2rain-server/g2rain-gateway-webflux`。

1. `GatewayTokenAuthFilter`：在设置 `entryId` 处一并 `context.setVisitorId(tokenPayload.getVisitorId())`。
2. 匿名会话校验：`ANONYMOUS` 令牌带非空 `visitorId` 时拒绝，写法同现有 `entryId` 校验。
3. `PrincipalForwardFilter`：确认新请求头随 `PrincipalHeaders.values()` 被先移除、再按上下文写入；不需要改代码时在交付说明里写明核对结果。

## 验收

- 测试：员工令牌的 `visitorId` 转发为 `x-visitor-id`；客户端自带的 `X-VISITOR-ID` 被移除；匿名令牌带 `visitorId` 被拒。
- 两个网关 `mvn clean verify` 通过（依赖 V01）。
