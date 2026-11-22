# 状态层重构：一个结构一个编解码 + 按变更写入

| 项 | 内容 |
|---|---|
| 状态 | R1、R2 已实现（2026-11-22） |
| 来源 | 《runtime-weaknesses.md》第三节“状态层复杂度”扶正思路第 2、3 条（第 1、4 条已在 S1-1、S3-1 完成） |
| 拆分 | **R1**：编解码归位（不改行为）→ **R2**：按变更写入（行为不变，写入量变小）。R1 完成并测试通过后再做 R2 |
| 涉及类 | `TurnSessionHydrate`、`TurnFlushPlan`、`HashCodec`、`DefaultTurnScope`；新增 `StateCodec`、`SpaceCodec`、`GroupTtl`（R1），`FlushLedger`（R2） |
| 不涉及 | Lua 脚本、Redis key 结构、MySQL 落库（`RuntimeStatePersistence`）、`EvidenceFlow` 的写入时机、哨兵定组与完整性校验的规则 |
| 关联文档 | [状态模型](../STATE_SPEC.md)；[S1-1 哨兵定组](state-group-recovery.md)；[S2-1 大报文](state-payload-cold.md)；[S3-1 收尾](state-turn-finish.md) |

---

## 1. 现状

### 1.1 编解码分散、读写不对称

| 结构 | 读 | 写 |
|---|---|---|
| 会话栅栏 `state` | `TurnSessionHydrate.readFence` | `TurnFlushPlan.writeFence`（跳过 `version`，null 字段 HDEL） |
| 实体 `entities` | `TurnSessionHydrate.readEntities` | `TurnFlushPlan.ops`，淘汰走 `DefaultTurnScope.evictedEntities` |
| 共享值（会话、任务） | `TurnSessionHydrate.readShared`（跳过 `MARK`） | `TurnFlushPlan`（任务共享另写 `MARK`） |
| 断点、前情 | `hotGroup` 中“空串即没有” | `writeTask` 中“没有就 `SET ""`” |
| 图 | `HashCodec.graphOf` | `HashCodec.graphHash` |
| 节点空间 | `TurnSessionHydrate.expandSpace` / `expandField` | `TurnSessionHydrate.encodeSpace` / `spillField`，**由 `TurnFlushPlan` 调用** |
| 节点证据 | `openSpace` 内联解析（跳过 `MARK`） | `writeTask` 内联 |
| 任务组 TTL | `TurnSessionHydrate.taskTtl` / `taskKeys`，**由 `TurnFlushPlan` 调用** | 同左 |

后果：同一个结构的规则（空值怎么表示、哪些字段跳过、占位怎么处理）分散在两个类里，改一边容易漏另一边。`TurnFlushPlan` 还因为节点空间编码和 TTL 反向依赖 `TurnSessionHydrate`。

### 1.2 每次 flush 都整体重写

每次工具调用至少 flush 两次（预登记、结算），每轮 ReAct 还要 flush 一次。每次 flush 都会把以下内容整体重写一遍：

- 全部实体（上限 `entities-max = 200`）和全部会话共享；
- 任务元数据、整张图、断点、前情、全部任务共享；
- **本轮打开过的每个节点**的空间全部字段和全部证据头。

其中有两个实际损耗：

1. **溢出字段每次都会重新落库**：节点空间里超过 `space-field-max-bytes`（64KB）的字段，每次 flush 都会调用 `spillField`，生成新的 `payloadId`，向 `task_node_payload` **插入一行新记录**。一个节点只要有一个大字段，每次工具调用都会在 MySQL 多出一行。
2. **flush 体积随状态增长**：200 个实体加上打开过的节点，每次 flush 动辄几十到上百 KB，挤占 `flush-max-bytes`，也增加 Redis 带宽。

## 2. R1：编解码归位（不改行为）

目标：**每种 Redis 结构的读和写只出现在一处**，`TurnFlushPlan` 只负责组装 ops，`TurnSessionHydrate` 只负责按哨兵规则取数。

### 2.1 `StateCodec`（新增，`final class`，静态方法）

按结构成对提供编码和解码。空值写法、`MARK` 处理、跳过字段都收在这里：

| 结构 | 解码 | 编码 |
|---|---|---|
| 会话栅栏 | `ConversationFence fence(String conversationId, Map<String,String> hash)` | `fenceHash`：不含 `version`、不含 null；`fenceCleared`：null 字段名（flush 时 HDEL） |
| 实体 | `Map<String,EntityEntry> entities(Map<String,String>)` | `Map<String,String> entitiesHash(Map<String,EntityEntry>)` |
| 共享值 | `Map<String,Sourced<?>> shared(Map<String,String>)`：跳过 `MARK` | `Map<String,String> sharedHash(Map<String,Sourced<?>>, boolean marked)`：`marked` 为 true 时带 `MARK`（任务共享要，会话共享不要） |
| 任务元数据 | `@Nullable TaskMetadata metadata(Map<String,String>)` | `Map<String,String> metadataHash(TaskMetadata)`：不含 `conversationId`、`taskId`、`version`，不含 null 字段 |
| 图 | `@Nullable TaskGraph graph(Map<String,String>)` | `Map<String,String> graphHash(TaskGraph)`（从 `HashCodec` 迁入） |
| 断点 / 前情 | `@Nullable TaskCheckpoint checkpoint(@Nullable String raw)`、`@Nullable Prior prior(@Nullable String raw)`：空白即 null | `String checkpointValue(@Nullable TaskCheckpoint)`、`String priorValue(@Nullable Prior)`：null 写 `""` |
| 节点证据 | `List<EvidenceHead> evidence(Map<String,String>)`：跳过 `MARK`，按轮次排序 | `Map<String,String> evidenceHash(List<EvidenceHead>)`：带 `MARK` |
| 写索引 | —（读取在 `EvidenceFlow`，用 `hGet`，不改） | `Map<String,String> writesHash(List<EvidenceHead>)`：只含有幂等键的写证据，带 `MARK` |
| 对话 / 记录 | `List<DialogueMessage> dialogue(List<String>)`、`List<TaskRecord> records(List<String>)`：列表倒序还原 | `String message(DialogueMessage)`、`String record(TaskRecord)` |

`HashCodec` 只保留通用能力：`toHash`、`toObject`、`MARK`。`graphHash`、`graphOf`、`GRAPH_EDGES`、`GRAPH_ORDER` 迁入 `StateCodec`。`deletedFields` 在 R1 中暂时保留，R2 删除。

### 2.2 `SpaceCodec`（新增，`@Component`）

节点空间需要读写 `task_node_payload` 来处理溢出，所以单独作为组件：

```java
/** 节点空间 → Redis Hash；单字段超过 space-field-max-bytes 时落库并换成溢出占位；带 MARK。 */
public Map<String, String> encode(String taskId, String nodeId, NodeSpace space)

/** Redis Hash → 节点空间：跳过 MARK，还原溢出占位；只有 MARK 时为 null。 */
public @Nullable NodeSpace decode(Map<String, String> hash)
```

`TurnSessionHydrate.encodeSpace`、`expandSpace`、`spillField`、`expandField` 整体迁入；`TurnSessionHydrate` 不再依赖 `PayloadRepository`。

### 2.3 `GroupTtl`（新增，`@Component`）

```java
/** 按任务粗状态选 TTL：SUSPENDED 用 task-ttl-suspended，其余用 task-ttl-running。 */
public Duration of(@Nullable TaskMetadata task)

/** 任务组全部 key 的续期：元数据为 TTL − sentinel-gap（下限 1ms），其余为 TTL。 */
public List<KeyTtl> renewals(SessionKeys keys, DefaultTurnScope turn, Duration ttl)
```

从 `TurnSessionHydrate.taskTtl` / `taskKeys` 迁入。完成后，`TurnFlushPlan` 不再依赖 `TurnSessionHydrate`。

### 2.4 R1 的验收

- 行为完全不变：S1-1 到 S3-1 的全部用例（T1～T16、W1～W9、V1～V7 等）**不改断言**，全部通过；
- 每个结构补一条“编码 → 解码”往返用例，包括空值、`MARK`、溢出字段。

## 3. R2：按变更写入

### 3.1 思路：对比上次写入的内容，而不是到处打“已修改”标记

在每个修改方法里手工标记“已修改”（脏标记）容易漏，漏一处就是静默丢数据。这里改用**对比**：本轮内记住每个 Redis 字段**最近一次确认的内容**（加载时读到的，或上次 flush 成功写入的），flush 时把当前编码结果和它逐字段比较，只写有差异的字段。

- 不需要改动 `DefaultTurnScope` 和 `DefaultNodeScope` 的任何修改方法，不存在“忘了打标记”的问题；
- 编码本来每次 flush 都要做，多出来的只是字符串比较；
- 记录在本轮视图上，本轮结束即丢弃。

### 3.2 `FlushLedger`（新增，`final class`，包内可见）

由 `DefaultTurnScope` 持有（新增字段和访问方法 `ledger()`），只记 Hash 字段和 String 值：

```java
/** 加载或 flush 成功后，记下某个 Hash 当前在 Redis 中的全部字段。 */
void seedHash(String key, Map<String, String> fields)

/** 同上，String 值。 */
void seedString(String key, @Nullable String value)

/** 与上次确认的内容比较：返回要 HSET 的字段和要 HDEL 的字段。未登记过的 key 视为空。 */
HashDiff diff(String key, Map<String, String> current)

/** String 值是否与上次确认的不同；未登记过视为不同。 */
boolean changed(String key, String current)

/** 本次 flush 成功后提交：把本次写入的内容记为已确认；被 DEL 的 key 移除。 */
void commit(LedgerDelta delta)
```

`HashDiff` 与 `LedgerDelta` 为包内 record。**只有 `store.flush` 返回成功后才 commit**；flush 失败或被栅栏拒绝时不 commit，下一次 flush 会重新发出同样的改动。

### 3.3 哪些结构走对比

| 结构 | 对比方式 | 说明 |
|---|---|---|
| 实体、会话共享 | Hash 对比 | 实体被淘汰时，字段从当前编码中消失，对比结果自然是 HDEL，**`evictedEntities` 机制删除** |
| 会话栅栏 | **不对比，每次全量写** | `renew.lua` 会在 flush 之外写入；每次 HSET 非空字段，null 字段显式 HDEL；不登记 ledger |
| 任务元数据、图、任务共享 | Hash 对比 | 内存中持有这些结构的全部内容，null 字段通过对比得出 HDEL，**`HashCodec.deletedFields` 删除** |
| 写索引 | **只增不删**：只 HSET 与已确认内容不同的条目，**从不 HDEL** | 内存中只有本轮打开过的节点的证据，不是写索引的全集，不能按差集删除 |
| 断点、前情 | String 对比 | |
| 节点空间 | Hash 对比，**比较溢出之前的内容** | 只有内容变化的字段才会进入 `SpaceCodec` 的溢出逻辑，没有变化的大字段**不再重复落库** |
| 节点证据 | Hash 对比 | |
| 图中未打开节点的空间、证据 | **不对比、不删除**：加载时已校验（`verified`）的节点什么都不写；本轮新建的节点只在第一次成功 flush 时写一次 `MARK` | 内存中没有这些节点的内容，按“当前只有 `MARK`”去对比会把 Redis 中的真实字段全部 HDEL（2026-11-22 R2 修复时发现） |
| 对话、记录、报文 | **不变** | 本来就是只写新增部分 |
| 各类 EXPIRE | **不变**，每次照发 | 体积很小，保证 TTL 有序（S1-1 不变式） |

### 3.4 登记时机（`TurnSessionHydrate`）

| 读取点 | 登记内容 |
|---|---|
| 实体、会话共享 | 读到的 Hash |
| 热组 | 元数据、图、任务共享的 Hash，断点、前情的原始值：**直接用 `hotGroup` 已经读到的数据登记，不再重复读 Redis**；写索引和各节点都不在加载时登记 |
| `openSpace`（热组按需打开） | 节点空间的 Hash **按溢出还原之后**的内容（与 3.3 中“比较溢出之前的内容”口径一致），证据 Hash |
| 冷组、本轮新建的任务和节点 | **不登记**：第一次 flush 会完整写入（S1-1 的冷组写回依赖这一点） |

### 3.5 收尾与删除

- `TurnFlushPlan.finish` 中被整组 DEL 的 key，在 commit 时从记录中移除。
- 冷组加载时删除的残留 key 本来就没有登记，不受影响。

### 3.6 效果与风险

- **效果**：对比结构每次 flush 只写变化的字段；会话栅栏每次全量写。常见的一次工具调用，只会写那一个节点的证据和少量空间字段、整份栅栏、对话；溢出字段不再每次落一行 MySQL。
- **风险一：编码结果不稳定**，比如 Map 的遍历顺序不同。后果只是多写，不会少写，属于安全方向。
- **风险二：本轮内 key 被淘汰，记录却认为它还在**。这时本轮不会补写它，下一轮完整性校验发现缺失，会走冷组，从 MySQL 恢复，而本轮结束时 persist 已把最新内存状态写入 MySQL。和 S1-1“轮中被淘汰”的处理一致。

## 4. 文档同步

| 位置 | 修改 |
|---|---|
| STATE_SPEC 4⑤ flush | “每次 flush 都整体重写本轮涉及的状态”改为“只写与本轮已确认内容不同的字段；对话、记录、报文只写新增部分；EXPIRE 每次照发” |
| STATE_SPEC 第 8 节约束 3 | 删除（每次 flush 整体重写） |
| `runtime-weaknesses.md` 第三节 | 标注扶正思路第 2、3 条已完成 |

---

## 5. 任务卡 R1（交给 Cursor）

**卡片 R1：编解码归位（不改行为）**

**前提**：先读本文第 1、2 节，以及 `docs/development/code-conventions.md` 的“编码细则”。

**步骤**：

1. 新增 `StateCodec`、`SpaceCodec`、`GroupTtl`，按第 2 节迁入代码；`TurnSessionHydrate`、`TurnFlushPlan` 改为调用它们。
2. 删除 `TurnSessionHydrate` 中迁走的方法；`TurnFlushPlan` 的构造参数去掉 `TurnSessionHydrate`，改为注入 `SpaceCodec`、`GroupTtl`。
3. 补往返用例（见下表）。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| C1 | 栅栏往返（含 null 字段） | 解码结果与原对象一致；编码不含 `version` 和 null 字段 |
| C2 | 共享值往返：`marked` 为 true / false | true 时带 `MARK`，解码时跳过 |
| C3 | 断点、前情：null 编码为 `""`，`""` 解码为 null | |
| C4 | 证据往返：乱序轮次 | 解码后按轮次排序，跳过 `MARK` |
| C5 | `SpaceCodec`：超长字段编码后为溢出占位，解码还原；只有 `MARK` 时解码为 null | |
| C6 | `GroupTtl.renewals` | 元数据为 TTL − gap，其余为 TTL，覆盖图中全部节点 |

**验收**：C1～C6 通过；**已有状态层用例全部通过且不改断言**；`TurnFlushPlan` 不再依赖 `TurnSessionHydrate`；IDEA 无新增告警。

## 6. 任务卡 R2（R1 通过后再交给 Cursor）

**卡片 R2：按变更写入**

**前提**：R1 已合入；先读本文第 3、4 节。

**步骤**：

1. 新增 `FlushLedger`（以及包内 record `HashDiff`、`LedgerDelta`），由 `DefaultTurnScope` 持有。
2. `TurnSessionHydrate` 按 3.4 登记。
3. `TurnFlushPlan` 中对 Hash 和 String 结构的写入全部改为“编码 → 对比 → 只发差异”；节点空间先对比溢出之前的内容，只对变化的字段做溢出编码；`store.flush` 成功后 commit。
4. 删除 `DefaultTurnScope.evictedEntities` 及相关方法、`HashCodec.deletedFields`。
5. 按第 4 节同步文档。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| L1 | 热组加载后，不做任何修改直接 flush | 对比结构无 HSET/SET/HDEL（只有 EXPIRE）；栅栏每次全量写 |
| L2 | 只修改一个节点的 `outputs` | 只对该节点空间 HSET `outputs` 字段（另有栅栏全量写） |
| L3 | 淘汰一个实体 | 对 `entities` 发出该字段的 HDEL |
| L4 | 节点空间某字段由有值变为 null | 发出该字段的 HDEL |
| L5 | 节点空间有一个超长字段，热组加载后连续 flush 两次且该字段不变 | `saveSpaceField` 一次也不被调用 |
| L6 | flush 被栅栏拒绝（返回 false） | 不 commit；下一次 flush 发出与上次相同的改动 |
| L7 | 新建任务的第一次 flush | 全量写入（含全部占位）；第二次 flush 对比结构不再重复写占位 |
| L8 | `finish` 删除任务组后 | 记录中不再有该组的 key |
| L9 | Redis 无 `state`，栅栏自 MySQL 回灌且带 `activeTaskId`，再 flush | ops 含 `userId`、`activeTaskId` 的 HSET |
| L10 | `clearLastTrace` 后 flush | ops 含 `lastTraceId` 的 HDEL |

**验收**：L1～L10 通过；已有状态层用例全部通过（只有断言“每次都整体重写”的用例可以按新行为调整，调整处须在交付中列出）；IDEA 无新增告警；文档已同步。
