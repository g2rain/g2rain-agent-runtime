# 状态层第三步：收尾原子化（P2-2，顺带 P2-5）

| 项 | 内容 |
|---|---|
| 状态 | 已实施（2026-11-22） |
| 修复条目 | 扶正清单 P2-2；顺带修复 P2-5（同一段代码） |
| 涉及类 | `TurnFlushPlan`、`DefaultRuntimeStateFacade`、`RuntimeStatePersistence`、`TurnSessionHydrate`、`EvidenceFlow`（只改 `apply` 的调用写法） |
| 不涉及 | `DefaultTurnScope`、仓储、Lua 脚本、表结构 |
| 关联文档 | [状态模型](../STATE_SPEC.md) 4⑥、5.8 |

---

## 1. 问题

### 1.1 P2-2：收尾分多次 flush

persist 成功后，`DefaultRuntimeStateFacade.persist` 的收尾分三次提交 Redis：

1. 每个被顶替的旧任务调用一次 `dropClosedTask`：追加记录、删除任务组；
2. 当前任务如果已结束，再调用一次 `dropClosedTask`，然后 `clearActiveTask`；
3. 最后 `apply(scope, true)`：清空 `lastTraceId`，写入栅栏（`activeTaskId` 被删除）。

**第 2 次和第 3 次之间崩溃**，Redis 会留下这样的状态：
- `state.activeTaskId` 还指向已删除的任务组，`lastTraceId` 也没清；
- MySQL 中任务已是终态，`chat_conversation.active_task_id` 已经为空。

下一轮完整性校验不通过，走冷组，从 MySQL 读回一个**终态任务**当作活动任务。之后 `start()` 新任务时，又会把它当作被顶替的任务再关闭一次，重复写入并重复追加 `records`。

**另一条路径**：persist 已成功，但收尾的 flush 本身失败（Redis 异常）。这时 Redis 中任务组完好、元数据是终态，`activeTaskId` 也还在，下一轮热组直接读回终态任务，后果同上。

### 1.2 P2-5：任务记录生成两次

`RuntimeStatePersistence.persistTurn` 为写入 MySQL 生成一次 `TaskRecord`，`persist` 收尾为追加 Redis `records` 又生成一次。两次的 `closedAt` 不同，同一任务在 MySQL 和 Redis 中的记录不一致。

## 2. 设计

### 2.1 收尾一次提交

persist 成功后，收尾的全部 Redis 改动合成**一次** `flush.lua` 调用：

| 部分 | 内容 |
|---|---|
| 关闭 | 每个已结束任务（被顶替的旧任务 + 本轮结束的当前任务）：`LPUSH_TRIM records`、DEL 整组 key（`keys.group`）、DEL 本轮涉及的报文 |
| 状态 | 当前任务已结束时先 `clearActiveTask`；再 `clearLastTrace`；然后按 `ops()` 生成栅栏、对话、实体、会话共享，以及仍在进行中的任务组的写入 |
| 报文 | `payloadOps()`（当前任务已结束时为空） |

- 栅栏比对只做一次，要么全部生效，要么全部不生效。
- `flush-max-bytes` 校验“关闭 + 状态”两部分，报文部分不计（与 S2-1 一致）。

### 2.2 记录只生成一次

`RuntimeStatePersistence.persistTurn` 返回本次写入的记录：`Map<String, TaskRecord>`（taskId → 记录），包含被顶替的旧任务和本轮结束的当前任务。收尾直接用这些记录追加到 Redis，不再重新生成。

persist 第一次失败、重试成功时，以重试那次返回的记录为准。

### 2.3 load 的兜底

收尾 flush 失败的路径（1.1 的“另一条路径”）无法靠原子化消除，所以在 `TurnSessionHydrate.load` 加一道兜底：

> 装载到的任务（热组或冷组）元数据已是终态 → 按“活动任务标识悬空”处理：清空 `activeTaskId`，本轮按无活动任务处理，记 warn 日志。

- 任务组不主动删除，等它自然过期。完整性校验与这个任务无关，因为 `activeTaskId` 已经清空，下一轮不会再读它。
- 残留：这种情况下，这条任务记录没有追加到 Redis `records`。之后 Redis `records` 只要非空，就不会回落 MySQL，所以 Redis 中会少这一条，但 MySQL 中的 `task_record` 是完整的。只有收尾 flush 失败才会出现，可以接受。

## 3. 代码改动

### 3.1 `TurnFlushPlan`

1. **`apply(DefaultTurnScope turn, boolean clearLastTrace)` 改为 `apply(DefaultTurnScope turn)`**：只用于轮中 flush，不再清 `lastTraceId`。
2. **新增**：

   ```java
   /**
    * 轮末收尾：已结束任务追加记录并删除任务组、清空活动任务与 lastTraceId，与本轮其余改动一次提交。
    *
    * @param turn    本轮视图（persist 已成功）
    * @param records persist 写入的任务记录，taskId → 记录
    */
   public void finish(DefaultTurnScope turn, Map<String, TaskRecord> records)
   ```

   做法：
   1. 用 `turn.closedTasks()` 和“当前任务已结束”两部分生成关闭 ops（顺序：先旧任务，后当前任务）：
      - 记录从 `records` 取，取不到就抛 `STATE_WRITE_FAILED`，说明 persist 与收尾不一致，不能静默跳过；
      - key 用 `keys.group(taskId, graph)`，报文 id 分别来自 `closed.payloadIds()` 和 `turn.allPayloadIds()`。
   2. 当前任务已结束时，调用 `turn.clearActiveTask()`。
   3. 调用 `turn.clearLastTrace()`。
   4. `state = 关闭 ops + ops(turn, keys)`，对 `state` 做 `flush-max-bytes` 校验，再拼上 `payloadOps`，一次提交。
3. `apply` 和 `finish` 共用的提交逻辑抽成私有方法，包括：大小校验、`store.flush`、栅栏不符抛 `STATE_FENCED`、`markDialogueFlushed` / `markPayloadsFlushed`、`StateSizeProbe.record`。
4. **删除**两个 `dropClosedTask` 方法。

### 3.2 `RuntimeStatePersistence.persistTurn`

- 返回值改为 `Map<String, TaskRecord>`（taskId → 本次写入的记录）；没有结束的任务时返回空 Map。
- `persistClosed` 和当前任务结束时的 `recordOf` 只调用一次，同一个对象既写进 MySQL 也放进返回值。

### 3.3 `DefaultRuntimeStateFacade`

- `persistOnce` 返回 `persistTurn` 的结果。
- `persist`：
  - 两次都失败时，行为不变：直接返回，`lastTraceId` 保留；
  - 成功时，只调用 `flushPlan.finish(scope, records)`。
- 其余 `flushPlan.apply(turn, false)` 改为 `flushPlan.apply(turn)`。

### 3.4 `TurnSessionHydrate.load`

在得到 `TaskGroup` 之后，如果 `TaskStatus.isTerminal(group.metadata().getStatus())`，就按“悬空”分支处理：清空栅栏中的 `activeTaskId`，不装载任务、图、断点等，也不打开节点。与 MySQL 中没有元数据走同一个分支，日志里注明原因是“终态”。

### 3.5 `EvidenceFlow`

只把 `flushPlan.apply(node.turn(), false)` 改为 `flushPlan.apply(node.turn())`，不改其他逻辑。

## 4. 文档同步（STATE_SPEC）

| 位置 | 修改 |
|---|---|
| 4⑥ “persist 成功后” | 改为：“已结束的任务追加 `TaskRecord`（与写入 MySQL 的是同一条）、删除整个任务组、清空 `activeTaskId` 与 `lastTraceId`，与本轮其余改动在**一次** flush 中提交” |
| 4② 2.5 | 补一句：“装载到的任务已是终态 → 清空 `activeTaskId`，按无活动任务处理” |
| 5.8 “记录”一行 | 补充“记录只生成一次” |
| 8 | 新增约束：“收尾 flush 失败时，该任务记录不会追加到 Redis `records`（MySQL 中完整）” |

审计第〇节：P2-2、P2-5 完成后再改为“已修复”。

---

## 5. 任务卡（交给 Cursor）

**卡片 S3-1：收尾原子化**

**前提**：先读本文第 2、3 节，STATE_SPEC 4⑥、5.8，以及 `docs/development/code-conventions.md` 的“编码细则”。只改本文开头表格“涉及类”中列出的类和文档。

**步骤**：

1. 按第 3 节改代码。
2. 更新已有测试：
   - `TurnFlushPlanTest` 的 T13 改为对 `finish` 断言；
   - `TurnSessionHydrateTest` 中的 `apply(first, true)` 改为 `finish(first, Map.of())`。
3. 补齐下表的用例。
4. 按第 4 节同步 STATE_SPEC。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| W1 | `finish`：当前任务已结束 | `store.flush` **只被调用一次**；ops 包含整组 DEL、报文 DEL、`LPUSH_TRIM records`，以及栅栏中 `activeTaskId`、`lastTraceId` 的 HDEL；不包含该任务组的 HSET |
| W2 | `finish`：本轮顶替了旧任务，当前任务进行中 | 只调用一次 `store.flush`；包含旧任务的整组 DEL 和记录；当前任务组照常写入；栅栏 `activeTaskId` 为当前任务 |
| W3 | `finish`：没有结束的任务 | 只调用一次 `store.flush`；`lastTraceId` 被清空；`records` 没有追加 |
| W4 | `finish`：`records` 中缺少某个已结束任务的记录 | 抛 `STATE_WRITE_FAILED`，`store.flush` 未被调用 |
| W5 | `finish`：栅栏不符（`flush` 返回 false） | 抛 `STATE_FENCED` |
| W6 | `persistTurn`：当前任务已结束 | 写入 `tasks.save` 的记录与返回 Map 中的记录是**同一个对象** |
| W7 | `load`：热组元数据为终态 | `activeTaskId` 被清空，`turn.task()` 为空 |
| W8 | `load`：冷组 MySQL 元数据为终态 | 同 W7 |
| W9 | `DefaultRuntimeStateFacade.persist`：persist 两次都失败 | 不调用 `finish`（可用 mock 验证） |

**验收**：W1～W9 全部通过；原有测试仍然通过；IDEA 无新增告警；STATE_SPEC 已同步。
