# V05 · Runtime：会话属主认员工与访客两个 ID

## 目标

员工登录后，`ownerSelect` 的全部调用处（会话列表、历史消息、删除等）与继续对话，都同时认员工自己与上下文中的访客 ID。

## 依据

- 设计：`g2rain-agent-runtime/docs/design/visitor-history.md`
- 代码：`service.impl.ConversationServiceImpl#ownerSelect` 及其调用处、`engine.state.TurnSessionHydrate#load`（会话 fence 的属主校验）、`dto.ChatConversationSelectDto`、`ChatConversationMapper.xml`

## 范围

只改 `g2rain-server/g2rain-agent-runtime`。

1. 属主：新增一个小类（如 `ConversationOwner`），从 `PrincipalContextHolder` 读取 `organId`、`userId`、`visitorId`，提供「属主 ID 集合」（`userId`，以及非空且不等于 `userId` 的 `visitorId`）与 `owns(Long rowUserId)`。各处属主判断统一用它，不在各处各写一遍。
2. 查询：`ChatConversationSelectDto` 手工新增 `userIds`（`List<Long>`），Mapper 查询条件手工追加 `user_id IN (...)`；`ownerSelect` 改为按 `organId` + `userIds` 查询。生成的代码不动。
3. 继续对话：`TurnSessionHydrate#load` 的属主校验改为 `owns(fence 的 userId)`；新建会话仍以当前 `userId` 为属主。
   - 会话热态（Redis `state` Hash 的 `userId`，即 `ConversationFence.userId`）记录的是会话属主。员工继续访客会话时，属主必须保持访客，不得改写为员工；只有属主为空时才写入当前 `userId`（现有逻辑如此，保持不变）。否则员工退出后访客将无法打开该会话。
   - Redis 热态按「机构 + 会话 ID」存取，不按用户区分；历史消息、删除等读取都先经过 `ownerSelect` 校验，不需要另外处理。
4. 文档：`docs/STATE_SPEC.md` 或会话相关文档中补充属主规则一句。

## 验收

- 测试：带 `visitorId` 时，访客会话与员工会话都能列出、读取、继续、删除；不带时只认 `userId`；访客 ID 属于其他机构时查不到（按 `organId` 过滤）；新建会话属主为员工。员工继续访客会话后，热态与表中的属主仍为访客，回到访客身份仍可打开。
- `mvn clean verify` 通过（依赖 V01）。
