# 第二批 B5：仓储（P2-6、P2-16、P2-17）

| 项 | 内容 |
|---|---|
| 状态 | 设计待确认 |
| 修复条目 | 扶正清单 P2-6、P2-16、P2-17 |
| 涉及类 | `DefaultRuntimeStateFacade`（P2-6）；`TaskInstanceMapper.xml`（P2-16）；`ConversationRepository`、`ChatConversationDao`、`ChatConversationService` / `ChatConversationServiceImpl`、`ChatConversationMapper.xml`（P2-17） |
| 不涉及 | 表结构、`EntityExtractor` 的合并逻辑 |
| 关联文档 | [状态模型](../STATE_SPEC.md) 2.3、3.2、4⑥ |

---

## 1. P2-6：persist 重试时实体 `mentions` 重复累加

**问题**：`persistOnce` 的第一行是 `entityExtractor.collect(scope)`，它会把本轮的成功证据和已完成节点的产出合并进实体索引，并累加 `mentions`。persist 第一次失败、重试时，`collect` 会再执行一次，同一批实体的 `mentions` 就被加了两次。

**修复**：把 `collect` 从 `persistOnce` 移到 `persist` 开头，在第一次尝试之前**只执行一次**；`persistOnce` 只负责落库和标记已落库。

## 2. P2-16：`skill_versions` 不随轮末落库

**问题**：`TaskInstanceMapper.advanceStateVersion`（轮末 CAS 更新）的 SET 中没有 `skill_versions`。任务首次插入之后才锁定的技能版本（续跑中新出现的技能）不会写进 MySQL，冷恢复后版本锁定会丢失。

**修复**：SET 中加上 `skill_versions = #{skillVersions, jdbcType=LONGVARCHAR}`。`fillTaskRow` 已经给 DTO 赋了这个字段，Java 代码不需要改。只改这条语句，不动生成的其他语句。

## 3. P2-17：会话行被整行回写

**问题**：

- `ConversationRepository.touch`：为了更新 `last_message_time`，先读出整行，再用通用的 `save` 全量写回。`state_version`、`active_task_id`、`session_shared` 会被一并写回读出时的旧值，存在丢失更新的窗口。
- S1-2 新增的 `bindActiveTask` 用的也是同样的“读整行再全量写回”写法，问题相同。

**修复**：改为两条只更新目标列的专用语句，不再读整行：

```xml
<!-- 刷新会话最后消息时间：只改 last_message_time -->
<update id="touchLastMessage">
    UPDATE chat_conversation
    SET last_message_time = #{time, jdbcType=TIMESTAMP},
        update_time = CURRENT_TIMESTAMP
    WHERE conversation_id = #{conversationId, jdbcType=VARCHAR}
      AND (delete_flag = 0 OR delete_flag IS NULL)
</update>

<!-- 写调用前修正会话活动任务：只改 active_task_id，不动 state_version -->
<update id="bindActiveTask">
    UPDATE chat_conversation
    SET active_task_id = #{taskId, jdbcType=VARCHAR},
        update_time = CURRENT_TIMESTAMP
    WHERE conversation_id = #{conversationId, jdbcType=VARCHAR}
      AND (active_task_id IS NULL OR active_task_id &lt;&gt; #{taskId, jdbcType=VARCHAR})
      AND (delete_flag = 0 OR delete_flag IS NULL)
</update>
```

- 两条语句都**不改** `version`，因为它们只改单列，不需要乐观锁，也不会让其他按 `version` 更新的语句冲突。
- 按 `advanceStateVersion` 的现有写法，在 `ChatConversationDao`、`ChatConversationService`、`ChatConversationServiceImpl` 中各加对应方法（参数用 `@Param`）。
- `ConversationRepository`：
  - `touch` 改为调用 `touchLastMessage(conversationId, Moments.now())`；
  - `bindActiveTask` 改为直接调用 `bindActiveTask(conversationId, taskId)`；
  - 删除两处“读整行”的逻辑。

## 4. 文档同步

| 位置 | 修改 |
|---|---|
| STATE_SPEC 2.3 `task_instance` | 写入说明补“轮末 CAS 同步 `skill_versions`” |
| STATE_SPEC 2.3 `chat_conversation` | 写入说明补“写消息时只刷新 `last_message_time`；写调用前只修正 `active_task_id`” |
| STATE_SPEC 4⑥ | 补一句“实体合并在 persist 首次尝试前执行一次，重试不重复合并” |

---

## 5. 任务卡（交给 Cursor）

**卡片 B5：仓储**

**前提**：先读本文全文，以及 `docs/development/code-conventions.md` 的“编码细则”。只改本文开头表格中列出的类、Mapper XML 和文档；Mapper XML 只新增或修改本文列出的语句。

**步骤**：

1. 按第 1～3 节改代码和 SQL。
2. 补齐下表的用例；更新 `ConversationRepositoryTest` 中依赖整行 `save` 的旧断言（U5、U6）。
3. 按第 4 节同步 STATE_SPEC。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| R1 | `persist`：第一次 `persistTurn` 抛异常，第二次成功 | `entityExtractor.collect` **只被调用一次** |
| R2 | `persist`：一次成功 | `collect` 调用一次（回归） |
| R3 | `insertMessage` | 调用 `touchLastMessage`；**不调用** `chatConversationService.save`，也不读取会话行 |
| R4 | `bindActiveTask` | 调用 Service 的 `bindActiveTask(conversationId, taskId)`；不读取会话行，也不调用 `save` |
| R5 | `TaskRepository.save` 走 CAS 分支（`skillVersions` 非空） | 传给 `advanceStateVersion` 的 DTO 带有 `skillVersions`（回归，确认 Java 侧已赋值） |

`advanceStateVersion` 的 SQL 改动没有单元测试覆盖，由你联调时核对 `task_instance.skill_versions`。

**验收**：R1～R5 全部通过；原有测试仍然通过；IDEA 无新增告警；STATE_SPEC 已同步。
