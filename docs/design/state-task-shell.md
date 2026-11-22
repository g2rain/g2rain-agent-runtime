# 状态层补充：写调用前补写任务壳（P1-4b）

| 项 | 内容 |
|---|---|
| 状态 | 已实施（2026-11-22） |
| 修复条目 | 扶正清单 P1-4b |
| 前置 | [状态层第一步：哨兵定组与冷恢复规整](state-group-recovery.md)（已完成） |
| 涉及类 | `EvidenceFlow`、`RuntimeStatePersistence`、`TaskRepository`、`GraphRepository`、`ConversationRepository` |
| 不涉及 | `TurnSessionHydrate`、`TurnFlushPlan`、`DefaultTurnScope`、Mapper XML、表结构 |
| 关联文档 | [状态模型](../STATE_SPEC.md) 1.3（写入点）、5.4（去重）、5.5（写结果不确定） |

---

## 1. 问题

S2 预登记写调用时，`STARTED` 证据会立即写入 MySQL（`tool_call_record`）。但任务行（`task_instance`）、图的节点与边（`task_graph_node` / `task_graph_edge`），以及会话上的活动任务（`chat_conversation.active_task_id`）要到轮末 persist 才写。

如果这一轮在 persist 之前崩溃，之后 Redis 中的任务组又过期、被淘汰或丢失，冷恢复读 MySQL 时会出现两种情况：

1. **新任务首轮**：MySQL 中没有任务行，冷组读不到元数据，只能按“活动任务标识悬空”清掉。`STARTED` 证据挂在已经丢失的 taskId 上，用户重新提出需求时会新建任务，按任务去重挡不住，同一个写操作可能被再执行一次。
2. **已有任务中新增的节点**（重新规划、委派子节点）：MySQL 图中没有这个节点，规整时遍历不到，它的 `STARTED` 证据不会改成 `UNKNOWN`。之后同任务的去重（`CallRepository.findLiveWrite`）一直命中 `STARTED`，这个写操作就永远是“进行中”。

另外，如果 Redis 的 `state` 也丢了，栅栏会从 MySQL 的 `chat_conversation` 回灌，而那里的 `active_task_id` 还没指向这个任务，冷恢复根本找不到它。

## 2. 设计

### 2.1 思路

写调用插入 `STARTED` 之前，先把**它在冷恢复时需要依赖的行**补写进 MySQL，统称为“任务壳”。规则是：

> **只插入 MySQL 中还不存在的行，已有的行一律不动。**

已有的行代表上一次 persist 的结果，轮中不去覆盖，避免 MySQL 里出现一半新一半旧的混合状态。新插入的行本来就只在内存中，写进去的是此刻的快照。

### 2.2 任务壳的内容

| 行 | 条件 | 写法 |
|---|---|---|
| `task_instance` | 库中没有这个 taskId | 插入当前元数据、任务共享、前情；`state_version` 写 **0**（表示还没有经过轮末落库），起始时间、traceId、来源消息照常写入 |
| `chat_message.task_id` | 上一项确实插入了 | 回填来源消息的 `task_id`（`ConversationRepository.bindTask`）。原本由轮末 `save` 返回“新建”时触发，壳先插入后，轮末就不会再触发，所以要在这里做 |
| `task_graph_node` | 库中没有这个 nodeId | 插入图中节点此刻的状态；本轮打开过的节点同时写入节点空间（`state` 列） |
| `task_graph_edge` | 库中没有这条边 | 插入 |
| `chat_conversation.active_task_id` | 与当前任务不同 | 改为当前 taskId，**不动 `state_version`** |

- **为什么 `state_version` 写 0**：轮末 `save` 遇到已有行时走 CAS，条件是 `state_version < 本轮版本`。如果壳写的是本轮版本，轮末就会被判为 `STATE_FENCED`。写 0 能保证轮末一定可以推进。
- **为什么不动会话的 `state_version`**：会话栅栏只由轮末 persist 推进。这里只修正 `active_task_id`，让 `state` 丢失时冷恢复也能找到任务。

### 2.3 时机与失败处理

在 `EvidenceFlow.prewrite` 中，`flushPlan.apply` 之后、`calls.insertStarted` 之前调用 `persistence.persistShell(turn)`，两者放在**同一个 try 块**中，失败处理沿用现有分支：

- 瞬时失败 → 证据改为 `UNKNOWN_PRE_CALL`，本轮不再发起同一个写操作；
- 永久失败 → 证据改为 `CANCELLED`，抛 `STATE_WRITE_FAILED`。

`persistShell` 本身是一个事务，任务行、节点、边、会话活动任务要么都写入，要么都不写。

**次数**：每次写调用都会执行一遍，但每张表只是“先查一次，缺了才插”。已经存在的行不会写，所以除首次以外，每次写调用只多 3～4 次查询。写调用本来就不频繁，这个代价可以接受，也不需要在本轮视图上记标记。

### 2.4 修复后的冷恢复

| 场景 | 结果 |
|---|---|
| 新任务首轮写调用后崩溃，任务组过期或丢失 | 冷组能读到任务行和图，规整时把 `STARTED` 改为 `UNKNOWN`，进入写结果核实，不会重复写入 |
| 已有任务中新增节点上的写调用后崩溃 | 冷组能读到新节点，同上 |
| 再加上 Redis `state` 丢失 | 栅栏从 `chat_conversation` 回灌，`active_task_id` 已指向该任务，同上 |
| 正常收尾 | 轮末 persist 照常覆盖任务行、节点和边（CAS 从 0 推进到本轮版本），结果与原来一致 |

### 2.5 残留

- 壳是写调用那一刻的快照。如果崩溃后冷恢复，任务会从这个快照继续，而之后在内存中的进展会丢失，与第一步文档 2.6 的残留风险 1 同类，不会重复写入。
- 被顶替的旧任务（本轮 `start` 新任务时关闭的那个）不补壳，它在 MySQL 中保持上一次 persist 的状态。

## 3. 代码改动

### 3.1 `TaskRepository`

```java
/**
 * 库中没有该任务时插入任务壳（state_version = 0）；已有行不动。
 *
 * @return 本次插入了任务行
 */
public boolean insertIfAbsent(TaskMetadata metadata, Map<String, Sourced<?>> shared, @Nullable Prior prior)
```

插入时字段的赋值与 `save` 的插入分支一致（复用 `applyOrigin`，以及同样的字段映射），只是 `stateVersion` 固定为 0、`taskRecord` 为空。如果能把字段映射抽成私有方法供 `save` 和 `insertIfAbsent` 共用，就抽出来，不要复制一遍。

### 3.2 `GraphRepository`

```java
/**
 * 插入库中尚不存在的节点与边；已有的节点行不动。
 *
 * @param spaces 本轮打开过的节点空间，新插入的节点有空间时一并写入 state
 */
public void insertMissing(String taskId, TaskGraph graph, Map<String, NodeSpace> spaces)
```

新节点的 DTO 构造与 `saveNodes` 的新节点分支一致，抽成私有方法共用。边直接复用 `saveEdges`，它本来就是只插入缺失的边。

### 3.3 `ConversationRepository`

```java
/**
 * 会话的活动任务不是 taskId 时改为 taskId；不动 state_version。
 */
public void bindActiveTask(String conversationId, String taskId)
```

写法与 `touch` 相同：先 `find`，不同则 `vo2dto` 后设置 `activeTaskId` 并 `save`。

### 3.4 `RuntimeStatePersistence`

```java
/**
 * 写调用登记前补写任务壳：任务行、缺失的节点与边、会话活动任务。只插入库中没有的行。
 */
@Transactional(rollbackFor = Exception.class)
public void persistShell(DefaultTurnScope scope)
```

- 没有活动任务时直接返回。
- `tasks.insertIfAbsent(...)` 返回 true 时，调用 `conversations.bindTask(sourceMessageId, taskId)`。
- `graphs.insertMissing(taskId, scope.graph(), spacesOf(scope.nodes()))`，复用本类已有的 `spacesOf`。
- `conversations.bindActiveTask(conversationId, taskId)`。

### 3.5 `EvidenceFlow`

- 构造器注入 `RuntimeStatePersistence`。
- 在 `prewrite` 的 try 块中，`calls.insertStarted(...)` 之前调用 `persistence.persistShell(node.turn())`。
- 类 Javadoc 和 `prewrite` 的 Javadoc 补充：登记 `STARTED` 之前会先补写任务壳。

## 4. 文档同步（STATE_SPEC）

| 位置 | 修改 |
|---|---|
| 1.3 写入点 S2 | 改为“一条 `STARTED` 证据：先 Redis，再同步写 MySQL（先补写任务壳，再插入 `tool_call_record`）” |
| 2.3 MySQL 表 `task_instance` | 写入时机补充“写调用前补写任务壳（`state_version = 0`）” |
| 5.4 证据与写操作去重 | `Proceed` 一行补充“补写任务壳” |
| 8 | 删除第 9 条（新任务首轮崩溃且任务未落库时无法冷恢复） |

同时把审计文档第〇节 P1-4b 一行的“处理”改为“已修复”（完成后再改）。

---

## 5. 任务卡（交给 Cursor）

**卡片 S1-2：写调用前补写任务壳**

**前提**：先读本文第 2、3 节，STATE_SPEC 1.3、2.3、5.4，以及 `docs/development/code-conventions.md` 的“编码细则”。只改本文开头表格“涉及类”中列出的类；不改 Mapper XML 和表结构。

**步骤**：

1. 先写测试，固定 `TaskRepository.save`、`GraphRepository.save` 的现有行为（已有行走 CAS 或更新，新行插入）。
2. 按第 3 节改代码。
3. 补齐下表的用例。
4. 按第 4 节同步 STATE_SPEC。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| U1 | `TaskRepository.insertIfAbsent`：库中没有任务 | 插入一行，`stateVersion = 0`，起始时间、traceId、来源消息已写；返回 true |
| U2 | `TaskRepository.insertIfAbsent`：库中已有任务 | 不写任何行；返回 false |
| U3 | 先 `insertIfAbsent`，再以本轮版本 V（V ≥ 1）调用 `save` | 走 CAS 分支，`advanceStateVersion` 收到 V，不抛 `STATE_FENCED` |
| U4 | `GraphRepository.insertMissing`：库中已有节点 A，图中有 A、B，B 已打开 | 只插入 B，且 B 带 `state`；A 没有任何写入；缺失的边被插入 |
| U5 | `ConversationRepository.bindActiveTask`：活动任务相同 | 不写 |
| U6 | `ConversationRepository.bindActiveTask`：活动任务不同 | 只改 `activeTaskId`，`stateVersion` 不变 |
| U7 | `RuntimeStatePersistence.persistShell`：新任务 | 依次调用 `insertIfAbsent`（返回 true）、`bindTask`、`insertMissing`、`bindActiveTask` |
| U8 | `RuntimeStatePersistence.persistShell`：任务已存在 | `insertIfAbsent` 返回 false，不调用 `bindTask`；`insertMissing`、`bindActiveTask` 仍被调用 |
| U9 | `RuntimeStatePersistence.persistShell`：没有活动任务 | 不调用任何仓储 |
| U10 | `EvidenceFlow.prewrite` 正常路径 | `persistShell` 在 `insertStarted` 之前被调用，返回 `Proceed` |
| U11 | `EvidenceFlow.prewrite`：`persistShell` 抛 `TransientDataAccessException` | 返回 `Blocked`，证据为 `UNKNOWN_PRE_CALL`；`insertStarted` 未被调用 |
| U12 | `EvidenceFlow.prewrite`：`persistShell` 抛其他 `DataAccessException` | 证据为 `CANCELLED`，抛 `STATE_WRITE_FAILED`；`insertStarted` 未被调用 |

**验收**：U1～U12 全部通过；原有测试仍然通过；IDEA 无新增告警；STATE_SPEC 已同步。
