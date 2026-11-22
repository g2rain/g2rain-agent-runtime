# 状态层第一步：哨兵定组与冷恢复规整

| 项 | 内容 |
|---|---|
| 状态 | 设计已确认（2026-11-22），待交 Cursor 实施 |
| 修复条目 | 扶正清单 P1-4（硬伤）、P1-3 |
| 涉及类 | `TurnSessionHydrate`、`TurnFlushPlan`、`SessionKeys`、`HashCodec`、`StateStore`、`RedisStateStore`、`DefaultTurnScope`（只加“已校验节点”集合） |
| 不涉及 | `DefaultRuntimeStateFacade`、`RuntimeStatePersistence`、`EvidenceFlow`，以及会话级 key（对话、记录、会话共享、实体） |
| 部署前提 | 无。Redis 保持共享实例和 `allkeys-lru`，不加部署要求 |
| 后续 | P1-4b（新任务首轮崩溃时补写任务壳）单独一张卡，在本步之后做 |
| 关联文档 | [状态模型](../STATE_SPEC.md)、[审计第〇节](../architecture/reviews/runtime-package-audit.md) |

---

## 1. 问题

1. **P1-3**：任务组按 key 逐个回灌，Redis 中缺哪个 key 就从 MySQL 读哪个。断点被消费时 flush 会 DEL 掉断点 key。如果这一轮 persist 两次都失败，下一轮 Redis 中没有断点，就回落 MySQL，旧断点又复活了。
2. **P1-4**：中断规整只在 `lastTraceId` 非空时触发，只处理 `RUNNING` 任务，也只读 Redis 中的证据。以下三条冷恢复路径会把 MySQL 中的 `STARTED` 写证据原样带回来，同一个写调用就会一直停在 `WRITE_IN_FLIGHT`：
   - 任务组过期后才回来；
   - 任务在 MySQL 中是 `SUSPENDED`；
   - Redis 的 `state` 丢失，`lastTraceId` 跟着丢失。
3. **“缺失”有歧义**：Redis 中缺一个 key，可能是本来就为空，比如断点被 DEL、共享值为空、节点从没打开过；也可能是过期了，或者在 `allkeys-lru` 下被淘汰了。代码区分不了，于是只能逐 key 回落 MySQL，P1-3 就是这么来的。
4. **哨兵 TTL 无序**：STATE_SPEC 2.1 说元数据是哨兵，TTL 比组内 key 短 `sentinel-gap`。但现在有两个问题：
   - flush 写元数据时用的是完整 TTL；
   - flush 只给本轮打开过的节点续期。任务从 `SUSPENDED`（2h）恢复到 `RUNNING`（24h）时，没打开过的节点 key 仍然只有 2h。

## 2. 设计

### 2.1 思路

**空值显式化**：组内每个 key，只要按组定义应该存在，就一定存在，空值也写入占位。这样“缺失”只剩一种含义：过期或被淘汰。

**哨兵定组 + 整组完整性校验**：
- 哨兵在，并且组内应有的 key 全部存在：热组，只读 Redis；
- 否则：冷组，整组以 MySQL 为准。

这个方案不依赖 Redis 的淘汰策略，也不依赖 TTL 是否有序。TTL 有序这一项照样修，只是为了减少不必要的冷恢复。

### 2.2 任务组的 key 与占位

任务组包括：元数据（哨兵）、图、断点、任务共享、前情、写索引，以及图中每个节点的空间和证据。报文 key 不在组内，它的读取本来就有 MySQL 回落，保持不变。

| key | 类型 | 空值的写法 |
|---|---|---|
| 元数据 | Hash | 不需要占位，任务存在时总有字段 |
| 图 | Hash | 不需要占位，`graphHash` 总会写 `_edges`、`_order` |
| 断点、前情 | String | 没有值时 `SET ""`，不再 `DEL` |
| 任务共享、写索引、节点空间、节点证据 | Hash | 每次 flush 写占位字段 `HashCodec.MARK`（字段名为空串 `""`，值为空串） |

- 每次 flush 都为**图中全部节点**写节点空间和节点证据的占位，不只是本轮打开过的。HSET 是幂等的，每次 flush 多 2N+2 个操作。
- 读取时的处理：
  - 空串的断点、前情按“没有”处理；
  - `HashCodec.toObject` 跳过 `MARK` 字段，没有真实字段时返回 `null`；
  - 读任务共享、读证据时跳过 `MARK` 字段；
  - `graphOf` 不受影响。
- 关于空串字段名会不会撞上真实字段：
  - 证据字段是 UUID，节点空间的字段是固定属性名，写索引的字段是“工具名 + 幂等键”，都不会是空串。
  - 任务共享的字段是契约里的输出键，空输出键属于契约配置错误，按“配置正确性由配置者负责”的立场，运行时不另加校验。真配了空输出键，读回时会被当作占位跳过。

### 2.3 回灌口径

```
activeTaskId 非空
 ├─ 读元数据（哨兵）与图
 │    ├─ 两者都在，且 countExisting(keys.group(taskId, graph)) == 组内 key 数
 │    │      → 热组：断点、共享、前情只读 Redis；节点空间按需打开，只读 Redis
 │    └─ 否则 → 冷组：
 │           ① 读 MySQL 元数据；没有 → 按现有“活动任务标识悬空”处理
 │           ② 删除 Redis 中残留的组内 key（Redis 图与 MySQL 图节点的并集）
 │           ③ 从 MySQL 整组读取：图、断点、任务共享、前情
 │           ④ 图中全部节点立即从 MySQL 打开（空间和证据），挂到本轮视图上
```

- **为什么冷组要一次打开全部节点**：本轮的 begin flush 会写回哨兵、写入全部节点的占位。没打开的节点如果只写了占位、没写真实数据，下一轮就会被当成空节点。全部打开后，第一次 flush 会把整组完整写回 Redis，下一轮就是合法的热组。
- **为什么删除残留 key**：冷组以 MySQL 为准，残留 key 里可能有 MySQL 已经没有的字段，混在一起就乱了。删除在会话锁（STATE_SPEC ①）里执行。
- **轮中发现 key 缺失**：只对**加载时已校验的节点**生效。热组 load 时把图中全部节点记为已校验（`DefaultTurnScope.markVerified`）。打开这类节点时，如果空间或证据 key 不存在（`hGetAll` 为空），说明它在本轮内被淘汰了：本轮抛 `STATE_UNAVAILABLE` 失败，下一轮 load 时完整性校验不通过，按冷组恢复。本轮新建的节点（新任务、重新规划、委派新增的子节点）在下一次 flush 写入占位之前，Redis 里本来就没有 key，打开时返回空节点，不抛异常。
- **代价**：
  - 热组每轮多一次 `EXISTS`。组内 key 都带 `{c}` hash tag，在同一个槽里，一次往返就能查完；
  - 冷组多读 2N 次 MySQL，并且整组写回一次。冷恢复很少发生。

### 2.4 规整口径

触发条件：`renewed.interrupted()`（旧 `lastTraceId` 非空），**或者本轮是冷组**。

| 动作 | 条件 |
|---|---|
| `RUNNING` 节点 → `READY` | 任务状态为 `RUNNING`（与现状相同） |
| `STARTED` 证据 → `UNKNOWN` | **不论任务状态**，遍历图中全部节点，读 `openSpace(...).evidence()` |

规整结果由 begin 中现有的那次 flush（STATE_SPEC 2.8）写入 Redis，persist 时再写入 MySQL。

### 2.5 各路径修复后的结果

| 路径 | 结果 |
|---|---|
| P1-3：断点已消费，persist 失败 | 热组只读 Redis，断点 key 的值为 `""`，视为没有断点，MySQL 中的旧断点不再被读取 |
| P1-4 路径 1：`state` 中有 `lastTraceId`，任务组已过期 | 冷组，规整后 `STARTED` → `UNKNOWN`，进入写结果核实（STATE_SPEC 5.5） |
| P1-4 路径 2：审批确认后派发写调用时崩溃，MySQL 中任务为 `SUSPENDED` | 冷组，规整不看任务状态，`STARTED` → `UNKNOWN`；断点以 MySQL 为准仍在，用户再次确认时去重命中 `UNKNOWN`，进入核实，不会重复写入 |
| P1-4 路径 3：`state` 丢失 | 任务组也丢失，或者被部分淘汰时，完整性校验不通过，进入冷组并规整。如果整组完好、只有 `state` 丢了，就是热组；这时 `lastTraceId` 丢失，不规整，Redis 中的 `STARTED` 会保留，属于残留风险 |
| LRU 淘汰了组内任意 key | 下一轮完整性校验不通过，按冷组恢复；轮中被淘汰则本轮失败，下一轮恢复 |

### 2.6 不变的部分与残留风险

- 写索引查找（`EvidenceFlow.findLiveEvidence`）在 Redis 未命中时仍查 MySQL，保留这个保守兜底。
- 报文读取的三级回落、节点字段溢出（`SpaceSpill`）都不变。
- 会话级 key 的回落不在本步范围内。
- **残留风险**：
  1. 被淘汰或过期的组如果上一轮 persist 失败过，MySQL 比 Redis 旧，冷恢复会丢掉那一轮没落库的进展（节点产出、共享值、图的推进、助手回复），任务退回到上一次成功 persist 时的状态并重做。写操作的证据在 S2、S3 时已同步写入 MySQL，不会重复写入。
  2. 只有 `state` 丢失而任务组完好时，识别不出中断（2.5 路径 3）。`state` 每轮都会被访问，LRU 下比空闲的组内 key 更难被淘汰，所以这种情况很少。
  3. 新任务首轮崩溃且 `task_instance` 还没落库：由 P1-4b 解决。
- **上线时**：已有的热组没有占位，完整性校验会不通过，所以会各走一次冷恢复，以 MySQL 为准。按“不做兼容”处理，不写迁移逻辑。

## 3. 代码改动

### 3.1 `StateStore` / `RedisStateStore`

新增：

```java
/**
 * 统计给定 key 中实际存在的个数（同一 hash tag 下一次往返）。
 */
long countExisting(List<String> keys);
```

实现用 `redis.stringRedisTemplate().countExistingKeys(keys)`，`null` 时按 0 处理。不修改 g2rain-common。

### 3.2 `SessionKeys`

新增方法，列出任务组的全部 key（不含报文），供完整性校验、续期、删除残留和收尾删除共用：

```java
/**
 * 任务组的全部 key：元数据、图、断点、任务共享、前情、写索引，以及图中每个节点的空间与证据（不含报文）。
 */
public List<String> group(String taskId, TaskGraph graph)
```

### 3.3 `HashCodec`

1. 新增常量 `MARK = ""`，Javadoc 说明它是“组内 Hash 的存在占位字段”。
2. `toObject`：跳过 `MARK` 字段；没有任何真实字段时返回 `null`。

### 3.3a `DefaultTurnScope`

新增“已校验节点”集合，只加以下内容，不改其他逻辑：

```java
/** 加载时已通过完整性校验的节点 id；其 Redis key 缺失即视为被淘汰。 */
private final Set<String> verified = new HashSet<>();

/** 记下加载时已校验的节点。 */
public void markVerified(Collection<String> nodeIds)

/** 节点是否在加载时已校验。 */
public boolean verified(String nodeId)
```

### 3.4 `TurnSessionHydrate`

1. **任务组读取**：新增私有 record `TaskGroup(TaskMetadata metadata, TaskGraph graph, @Nullable TaskCheckpoint checkpoint, Map<String, Sourced<?>> shared, @Nullable Prior prior, boolean cold)`，以及两个方法：
   - `hotGroup(...)`：按 2.3 判定，通过则只读 Redis，否则返回 `null`；
   - `coldGroup(...)`：按 2.3 的冷组步骤执行，MySQL 中没有元数据时返回 `null`，由调用方按悬空处理。

   `load` 中原来逐 key 的“Redis 优先 / 缺失回落”全部删除。

   热组构造 `DefaultTurnScope` 后，调用 `turn.markVerified(图中全部节点 id)`。
2. **冷组打开全部节点**：构造 `DefaultTurnScope` 后，如果 `group.cold()`，对图中每个节点用 MySQL 数据（`graphs.loadSpace`、`calls.loadNodeEvidence`）构造节点视图并挂上。构造节点视图的代码抽成私有方法 `attach(turn, nodeId, space, evidence)`，热路径和冷路径共用，证据按轮次排序也放在这里。
3. **`openSpace`**：
   - 去掉 MySQL 回落；
   - 空间或证据 key 的 `hGetAll` 为空时：`turn.verified(nodeId)` 为真则抛 `STATE_UNAVAILABLE`，否则返回空节点（本轮新建的节点）；
   - 读证据时跳过 `MARK` 字段。
4. **读断点和前情**：空串按“没有”处理。
5. **`readShared`**：跳过 `MARK` 字段。会话共享不在组内，但共用这个方法，跳过也不影响它。
6. **`taskKeys`**：改为基于 `keys.group(taskId, turn.graph())` 生成，元数据用 `TTL − sentinel-gap`（下限 1ms），其余用 `TTL`。
7. **规整**：触发条件改为 `renewed.interrupted() || group.cold()`。`tidyInterrupt` 改为：

   ```java
   turn.task().ifPresent(meta -> {
       boolean running = Objects.equals(meta.getStatus(), TaskStatus.RUNNING);
       turn.graph().getNodes().forEach(node -> {
           if (running && Objects.equals(node.getStatus(), NodeStatus.RUNNING)) {
               turn.mark(node.getNodeId(), NodeStatus.READY, null, null);
           }
           DefaultNodeScope opened = openSpace(turn, keys, node.getNodeId());
           opened.evidence().stream()
               .filter(head -> Objects.equals(head.status(), EvidenceStatus.STARTED))
               .toList()
               .forEach(head -> opened.addEvidence(head.withStatus(EvidenceStatus.UNKNOWN)));
       });
   });
   ```

   删除 `rewriteStarted`。
8. 类 Javadoc 改写为“哨兵定组 + 完整性校验”的口径。

### 3.5 `TurnFlushPlan.writeTask`

1. 断点、前情没有值时改为 `FlushOp.set(key, "", ttlMs)`，不再 DEL。
2. 任务共享、写索引，以及图中**每个**节点的空间和证据，都追加 `FlushOp.hset(key, HashCodec.MARK, "", ttlMs)`。
3. 末尾追加 `hydrate.taskKeys(keys, turn, ttl)` 中每个 key 的 `FlushOp.expire`。这些操作排在全部写操作之后，最终 TTL 以它们为准。
4. `dropClosedTask` 改用 `keys.group(taskId, graph)` 生成 DEL，再加上报文 DEL。

## 4. 文档同步（STATE_SPEC）

| 位置 | 修改 |
|---|---|
| 1.2 MySQL 一行 | 任务组按“哨兵 + 完整性校验”整组回灌；会话级 key 缺失时仍从 MySQL 回灌 |
| 2.1 Redis Key | 断点、前情为空时值为 `""`；组内 Hash 带占位字段 `""`；新增“任务组完整性”小节（2.1、2.2 的口径） |
| 2.1 任务组 TTL | renew 和每次 flush 都对整组统一设置 TTL，元数据为 `TTL − sentinel-gap` |
| 4② 2.5 | 改为 2.3 的流程 |
| 4② 2.7 | 触发条件改为“旧 `lastTraceId` 非空，或本轮为冷组” |
| 5.2 | 规整口径按 2.4 改写 |
| 8 约束 2 | 删除；新增约束：“只有 `state` 丢失而任务组完好时识别不出中断”“新任务首轮崩溃且任务未落库时无法冷恢复（P1-4b 修复后删除）” |

---

## 5. 任务卡（交给 Cursor）

**卡片 S1-1：哨兵定组与冷恢复规整**

**前提**：
- 先读本文第 2、3 节，以及 STATE_SPEC 第 2、4、5 节；
- 不改本文开头表格“不涉及”中列出的类；
- 遵守项目编码规范：
  - 不递归，不用散落的字面量 key（占位统一用 `HashCodec.MARK`）；
  - 判空用 `Objects.isNull`、`Objects.nonNull`；
  - IDEA 无告警；
  - 新类型的 Javadoc 带 `@author alpha`、`@since 2026/11/22`。

**步骤**：

1. **先建测试设施**：在 `src/test/java/com/g2rain/agent/runtime/engine/state/` 下新建 `MemoryStateStore`，只放在测试目录：
   - 实现 `StateStore`，用内存模拟 Hash、String、List，并记录每个 key 的 TTL，提供 `evict(key)` 来模拟淘汰；
   - `flush` 按 `flush.lua` 的语义执行，包括栅栏比对；
   - `renew` 按 `renew.lua` 的语义执行。

   仓储用 Mockito mock。
2. **按第 3 节改代码**。
3. **写 `TurnSessionHydrateTest`、`TurnFlushPlanTest`**，用例见下表。标注 P1-3 / P1-4 的用例在修改前应当失败。
4. **按第 4 节同步 STATE_SPEC**。
5. 改完交回，由你自己编译和测试。

**用例**：

| # | 场景 | 期望 | 对应 |
|---|---|---|---|
| T1 | 热组，断点 key 值为 `""`，MySQL 有断点 | 断点为空；`tasks.loadCheckpoint` 未被调用 | P1-3 |
| T2 | 热组，任务共享只有 `MARK`，前情为 `""`，MySQL 有值 | 为空；MySQL 方法未被调用 | P1-3 |
| T3 | 热组，打开节点，空间和证据只有 `MARK` | 空节点；`graphs.loadSpace`、`calls.loadNodeEvidence` 未被调用 | 口径 |
| T4 | 哨兵在，但 `evict` 了一个未打开节点的空间 key | 判为冷组；`store.del` 收到整组 key；全部节点从 MySQL 挂上 | LRU |
| T5 | 冷组，MySQL 任务为 `SUSPENDED`，节点 A 有 `STARTED` 写证据，`lastTraceId` 为空 | A 的证据变为 `UNKNOWN` | P1-4 路径 2、3 |
| T6 | 冷组，`state` 中有 `lastTraceId`，任务为 `RUNNING`，有一个 `RUNNING` 节点 | 节点变为 `READY`；`STARTED` 变为 `UNKNOWN` | P1-4 路径 1 |
| T7 | 热组且中断，任务为 `SUSPENDED`，Redis 证据为 `STARTED` | 变为 `UNKNOWN` | P1-4 |
| T8 | 热组且未中断 | 不规整，也不打开任何节点 | 回归 |
| T9 | 冷组，MySQL 中也没有元数据 | 活动任务标识被清空，按无任务处理 | 回归 |
| T10 | 热组，load 后 `evict` 某节点的证据 key，再 `openSpace` 该节点 | 抛 `STATE_UNAVAILABLE` | 轮中淘汰 |
| T11 | `TurnFlushPlan.ops`：图中 3 个节点，本轮只打开 1 个，任务从 `SUSPENDED` 转为 `RUNNING`，没有断点和前情 | 3 个节点的空间和证据都有 `MARK` 写入；断点、前情为 `SET ""`；每个组内 key 最后一次 TTL：元数据为 `24h − 10m`，其余为 `24h` | 占位与 TTL |
| T12 | 冷组 load 之后执行 flush，再执行一次 load | 第二次 load 判为热组，数据与 MySQL 一致 | 冷组物化 |
| T13 | `dropClosedTask` | DEL 的 key 集合等于 `keys.group(...)` 加上报文 | 回归 |
| T14 | `HashCodec.toObject`：只有 `MARK` | 返回 `null` | 占位 |
| T15 | 本轮新建任务（Redis 中没有任务组），图中有节点，直接 `openSpace` | 返回空节点，不抛异常 | 新建节点 |
| T16 | 热组加载后，图中新增一个节点，`openSpace` 该节点 | 返回空节点，不抛异常；对加载时已有的节点 `evict` 后打开仍抛 `STATE_UNAVAILABLE` | 新建节点 |

**验收**：T1～T16 全部通过；IDEA 无新增告警；STATE_SPEC 已同步。
