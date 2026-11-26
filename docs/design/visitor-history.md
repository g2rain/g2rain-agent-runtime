# 员工登录后延续访客聊天

## 1. 需求

chat-shell 中，访客在某个浏览器聊过天，之后在同一浏览器以员工身份登录：

- 员工能看到并继续使用这些访客聊天，同时看到员工自己的聊天。
- 员工退出、回到访客后，访客仍能看到访客的聊天。

聊天的归属不变：访客聊天仍属于访客，员工新建的聊天属于员工。员工只是「也能看到」当前浏览器的访客聊天。

## 2. 思路

访客身份由「机构 + DPoP 公钥指纹」解析（Basis `AnonymousVisitorServiceImpl#resolve`），员工在同一浏览器登录时使用同一把密钥（chat-shell 只清令牌，保留密钥）。因此：

1. IAM 签发员工令牌时，用令牌绑定的公钥算出指纹，到 Basis 查「令牌所属机构 + 指纹」是否有访客（只查不建），有则写入令牌的 `visitorId`。
2. 网关把 `visitorId` 放进上下文，以请求头 `X-VISITOR-ID` 转发；业务服务通过 starter 的 `PrincipalContextFilter` 还原到 `PrincipalContext`。
3. Runtime 查会话、继续对话时，属主认「当前 `userId`」或「上下文中的 `visitorId`」。

不新增表，不保存员工与访客的长期关联：关联只在当前浏览器（当前密钥）有效。

## 3. 规则

| 规则 | 说明 |
| --- | --- |
| 只认同一机构 | 只查令牌所属机构下的访客 |
| 只给非匿名令牌 | `ANONYMOUS` 会话的 `userId` 本身就是访客 ID，不带 `visitorId`；网关遇到带 `visitorId` 的匿名令牌拒绝（失败关闭） |
| 每次签发都重算 | 登录、刷新、切换机构、会员换票都经过同一个签发入口，入口内重新查询并覆盖 `visitorId`（没有访客时置空），不沿用旧令牌的值 |
| 查询失败不阻断登录 | Basis 查询失败时记日志、`visitorId` 置空，令牌照常签发 |
| 客户端无法伪造 | 网关转发前移除客户端带来的全部身份请求头再写入可信值；新请求头随 `PrincipalHeaders` 枚举自动纳入 |
| 新会话归员工 | 员工登录后新建的会话 `user_id` 为员工 |
| 访客会话的操作 | 员工对当前浏览器的访客会话与自己的会话权限相同：查看、继续对话、删除等 |

## 4. 改动

| 卡 | 项目 | 内容 |
| --- | --- | --- |
| [V01](../development/tasks/V01-common-visitor-id.md) | g2rain-common | `BasePrincipal.visitorId`、`PrincipalHeaders.VISITOR_ID`、`PrincipalContext` 读写分支、`PrincipalContextHolder.getVisitorId()` |
| [V02](../development/tasks/V02-basis-visitor-lookup.md) | g2rain-basis | 按「机构 + 指纹」只查不建的访客查询接口 |
| [V03](../development/tasks/V03-iam-visitor-id.md) | g2rain-iam | 签发入口写入 `visitorId` |
| [V04](../development/tasks/V04-gateway-visitor-id.md) | 网关 webmvc、webflux | 上下文写入 `visitorId`，匿名令牌校验 |
| [V05](../development/tasks/V05-runtime-visitor-history.md) | g2rain-agent-runtime | 会话属主认员工与访客两个 ID |

starter 不改代码：`PrincipalContextFilter`、Feign 转发、访问日志都按 `PrincipalHeaders.values()` 处理，升级 common 后重新发版即可。

发版顺序：common → starter → Basis → IAM → 网关 → Runtime。未升级的服务收到 `X-VISITOR-ID` 会忽略，不受影响。

chat-shell 不改。
