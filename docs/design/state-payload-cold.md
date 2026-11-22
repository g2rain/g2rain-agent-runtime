# 状态层第二步（一）：大报文只进 MySQL（P1-2，方案 B）

| 项 | 内容 |
|---|---|
| 状态 | 已实施（2026-11-22） |
| 修复条目 | 扶正清单 P1-2（方案 B，2026-11-22 确定），顺带修复 P2-4 |
| 涉及类 | `EvidenceFlow`、`TurnFlushPlan` |
| 不涉及 | `DefaultTurnScope`、`DefaultRuntimeStateFacade`（读取的回落链路已经满足要求）、`TurnSessionHydrate`、仓储、表结构 |
| 关联文档 | [状态模型](../STATE_SPEC.md) 4⑤、5.4（S3 结算）、5.10、第 7 节配置 |

---

## 1. 问题

1. **P1-2**：报文和状态改动放在同一批 flush 操作里，一起受 `flush-max-bytes`（1MB）约束。报文作为字符串放进 JSON，引号会被转义，体积最多膨胀近一倍，所以一个接近 512KB 的报文再加上状态改动，就可能超过 1MB。这时工具已经执行了，flush 却抛 `STATE_WRITE_FAILED`；收尾时再 flush 一次，报文还在待写列表里，仍然失败，persist 被跳过。
2. **P2-4**：超过 `payload-inline-max-bytes` 的报文，证据头上的 `payloadId` 只进了 Redis。`settle` 和 `record` 写 MySQL 时用的是没有 `payloadId` 的证据头，所以 `tool_call_record.payload_id` 一直为空。
3. **大报文占用共享 Redis**：Redis 是多个服务共用的 128MB 实例，淘汰策略是 `allkeys-lru`。几百 KB 的报文写进去，会挤掉其他服务和其他会话的 key。

## 2. 设计

### 2.1 报文的存放规则

| 报文大小 | Redis | MySQL | 证据头 `payloadId` |
|---|---|---|---|
| ≤ `payload-inline-max-bytes` | 存完整报文（与现在相同） | `tool_call_record.response` | 空 |
| > `payload-inline-max-bytes` | **不存** | `task_node_payload`（`ARTIFACT`），`tool_call_record.response` 为空 | `= evidenceId`，**并落到 `tool_call_record.payload_id`** |

读取顺序不变：本轮内存 → Redis → `tool_call_record.response` → `task_node_payload`。大报文在 Redis 中读不到，会走到最后一级。

### 2.2 flush 上限只约束状态

- `flush-max-bytes` 只校验状态改动，不含报文的 SET 操作。
- 报文本身的大小已经由 `payload-inline-max-bytes` 限制（只有不超过它的才进 Redis），不再需要第二道限制。
- 报文操作和状态改动仍然在**同一次** `flush.lua` 调用里提交，栅栏语义不变。

### 2.3 写入顺序与失败处理

顺序与现在相同：先更新 Redis 中的证据头（`settle` 时证据会从 `STARTED` 变成终态），再写 MySQL（调用行 + ARTIFACT）。

- 写 ARTIFACT 失败时，本轮报 `STATE_WRITE_FAILED`。`record` 现在就是这样处理的；`settle` 中的异常本来也会向上抛出，只需保证不被吞掉。
- 证据头已经结算，不会卡在 `STARTED`。代价是这份大报文丢失，之后读到的是 JSON null，这和 Redis 丢失报文的后果一样。

### 2.4 代价

超过 512KB 的报文，本轮再次读取时（比如 ReAct 下一轮重建历史）会从 MySQL 读，而不是 Redis。这种报文很少，可以接受。以后如果需要，可以在本轮视图上加一层只读缓存，本步不做。

## 3. 代码改动

### 3.1 `EvidenceFlow`

1. **`persistPayload`** 改为返回落库要用的证据头：

   ```java
   /**
    * 证据头写入热态；报文不超过内联上限时进 Redis，超过时只在证据头上记 payloadId（报文另存 MySQL）。
    *
    * @return 带 payloadId（超限时）的证据头，供随后落库使用
    */
   private EvidenceHead persistPayload(DefaultNodeScope node, EvidenceHead head, ToolResult result, @Nullable String body)
   ```

   - 超限时：`stored = head.withPayloadId(head.evidenceId())`，**不调用** `putPayload`；
   - 不超限时：`stored = head`，照常 `putPayload`；
   - 两种情况都执行 `node.addEvidence(stored)` 和 `flushPlan.apply(...)`。
2. **`settle`**：报文 JSON 只计算一次（`String body = json(result.payload())`），然后依次调用 `persistPayload(...)` 得到 `stored`，再把 `stored` 和 `body` 传给 `persistCallBody`。`persistCallBody` 改为接收 `body`，不再自己计算。
3. **`record`**：同样只计算一次 `body`，`startedDto(node, call, stored)` 使用 `stored`，这样 `payloadId` 会落库。
4. 把类和方法 Javadoc 中“Redis 始终写完整报文”改为 2.1 的口径。

### 3.2 `TurnFlushPlan`

1. 把报文 SET（现在 `writeTask` 末尾的 `turn.payloads().forEach(... writePayload ...)`）移到新的私有方法：

   ```java
   /**
    * 本轮待写报文的 Redis 改动；不计入 flush-max-bytes。
    */
   private List<FlushOp> payloadOps(DefaultTurnScope turn, SessionKeys keys)
   ```

   没有活动任务时返回空列表。需要的 taskId、TTL、受保护节点等，按 `writeTask` 中的方式取得。
2. **`apply`**：
   - `List<FlushOp> state = ops(turn, keys)`，**只对 `state` 做 `flush-max-bytes` 校验**；
   - 再把 `state` 和 `payloadOps(...)` 拼成一个列表提交给 `store.flush`。
   - `StateSizeProbe.record` 继续记录完整列表。
3. `ops()` 不再包含报文 SET。`shrinkDonePayloads` 产生的报文 EXPIRE / DEL 体积很小，仍留在 `ops()` 中。

## 4. 文档同步

| 位置 | 修改 |
|---|---|
| STATE_SPEC 4⑤ 失败表“改动超过 `state.flush-max-bytes`” | 改为“状态改动（不含报文）超过 `state.flush-max-bytes`” |
| STATE_SPEC 5.4 S3 结算 | 报文不超过阈值时写入 Redis；超过时不进 Redis，只存 `task_node_payload`，证据头与 `tool_call_record.payload_id` 记录 `payloadId` |
| STATE_SPEC 5.10 大对象表“工具报文”一行 | 按 2.1 改写 |
| STATE_SPEC 第 7 节配置 | `payload-inline-max-bytes`：“超过即只存 MySQL，不进 Redis”；`flush-max-bytes`：“单次 flush 状态改动上限（不含报文）” |
| 审计第〇节 | P1-2 处理改为“方案 B，已修复”；P2-4 改为“随 P1-2 修复”（完成后再改） |

---

## 5. 任务卡（交给 Cursor）

**卡片 S2-1：大报文只进 MySQL**

**前提**：先读本文第 2、3 节，STATE_SPEC 5.4、5.10，以及 `docs/development/code-conventions.md` 的“编码细则”。只改 `EvidenceFlow`、`TurnFlushPlan` 两个类和文档。

**步骤**：

1. 按第 3 节改代码。
2. 写用例（放在 `EvidenceFlowTest`、`TurnFlushPlanTest`）。
3. 按第 4 节同步文档。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| V1 | `record`：报文超过内联上限 | `turn.payloads()` 中没有该报文；插入的调用行 `payloadId = evidenceId`、`response` 为空；`saveArtifact` 被调用 |
| V2 | `settle`：报文超过内联上限 | `turn.payloads()` 中没有该报文；`calls.update` 收到的证据头 `payloadId = evidenceId`；`saveArtifact` 被调用 |
| V3 | `record`：报文不超过上限 | `turn.payloads()` 中有该报文；调用行 `response` 为报文、`payloadId` 为空；`saveArtifact` 未被调用 |
| V4 | `settle`：`saveArtifact` 抛异常 | 异常向上抛出（不被吞掉）；Redis 中证据头已是结算后的状态，不是 `STARTED` |
| V5 | `TurnFlushPlan.apply`：状态改动很小，待写报文是一个约 900KB 的 JSON 字符串（含大量引号） | 不抛异常；`store.flush` 收到的列表中包含该报文的 SET |
| V6 | `TurnFlushPlan.apply`：状态改动本身超过 `flush-max-bytes` | 仍抛 `STATE_WRITE_FAILED` |
| V7 | `TurnFlushPlan.ops` | 不包含报文 SET；原有 T11、T13 用例照常通过（如有断言依赖报文 SET 的，改为断言 `apply` 提交的列表） |

**验收**：V1～V7 全部通过；原有测试仍然通过；IDEA 无新增告警；文档已同步。
