# 第二批 B1：证据读取范围与前序调用参数（P1-1、X-1）

| 项 | 内容 |
|---|---|
| 状态 | 已实现 |
| 修复条目 | 扶正清单 P1-1（硬伤）、X-1（跨项目，本体审计 P1-4、agent-context 审计 P1-2 依赖它） |
| 涉及类 | `EvidenceReader`、`NodeBuiltinTools`、`RuntimeStateFacade` / `DefaultRuntimeStateFacade`、`CallRepository`；`ToolCallRecordDao`、`ToolCallRecordService` / `ToolCallRecordServiceImpl`、`ToolCallRecordMapper.xml`（只新增一条查询） |
| 不涉及 | `SourceVerifier`（调用点不变）、`EvidenceHead`、Redis 结构、表结构、SPI（`Call`、`CallView`、`ToolGuard`） |
| 不在本卡 | D-4（节点历史里的调用参数用摘要）：口径需要你另行决定，见第 4 节 |
| 关联文档 | [契约规范](../CONTRACT_SPEC.md) 来源定义；[状态模型](../STATE_SPEC.md) 5.6 |

---

## 1. 问题

### 1.1 P1-1：证据读取不限作用域

`builtin_evidence_get` 和来源核验中的 `EVIDENCE` 分支（`SourceVerifier.verify` → `EvidenceReader.matches`）都直接调用 `RuntimeStateFacade.payload(evidenceId)`。这个方法依次查：本轮内存 → 本任务 Redis → `tool_call_record.response` → `task_node_payload`。后两步**只按 ID 查询**，租户拦截器只追加 `organ_id` 条件。

所以模型只要给出同一机构下任意一个 evidenceId，就能：
- 读到其他会话、其他用户的完整报文（未脱敏）；
- 在 `builtin_output_set` 中把它声明为来源，并通过核验。

CONTRACT_SPEC 对 `EVIDENCE` 来源的定义是：“本节点或任一上游节点（沿依赖边追溯，含委派）的某条**成功**证据”。`ArgBinding`、`SourceResolver` 用的 `successfulEvidenceNewestFirst` 守住了这个范围，唯独这两个入口没有守。

### 1.2 X-1：前序调用参数是摘要

`EvidenceReader.nodeCalls` 把证据头上的 `argsBrief` 解析为前序调用的参数，交给 `ToolGuard`。`argsBrief` 已脱敏（敏感字段为 `***`）、已截短（字符串叶子截断，容器过大时折叠），本体用它判断“是否已补查过某个主键”时会匹配不上，于是反复要求补查。

完整参数只存在 MySQL 中（`tool_call_record.request`）。读工具在结算时插入，写工具在预登记时插入，所以能作为 Guard 判断依据的成功调用，在 MySQL 中都有完整参数。

## 2. 设计

### 2.1 证据可达范围（P1-1）

| 入口 | 允许的证据 |
|---|---|
| `builtin_evidence_get` | 本节点或上游节点的**任意状态**证据（模型可能要看失败调用的报文） |
| 来源核验 `EVIDENCE`（`matches`） | 本节点或上游节点的**成功**证据（按 CONTRACT_SPEC） |

- “上游节点”沿用 `TaskGraph.upstream(nodeId)`，与 `successfulEvidenceNewestFirst` 的口径一致。
- 判定通过才读报文；不通过时**不调用** `payload`，避免任何读取。
- `RuntimeStateFacade.payload` 保持为内部读取，不加范围限制：写操作复用（`adopt`）需要读同任务其他节点的报文，这是运行时自己的行为，不是模型的请求。

`builtin_evidence_get` 不通过时，抛 `ToolRejectedException(EVIDENCE_PATH_INVALID, ModelHint.NEXT_EVIDENCE_ID.text(), 说明)`，复用现有错误码，不新增 i18n。说明文案写法与同文件中“evidenceId 为空”一致。

### 2.2 前序调用的完整参数（X-1）

- `nodeCalls` 先收集成功证据，再用**一次批量查询**按 evidenceId 从 `tool_call_record` 取 `request`，解析为对象作为 `Call.arguments`。
- 这个查询只取 `call_id` 和 `request` 两列，不取 `response`，避免带出大报文。
- 某条证据查不到 `request`，或解析不是 JSON 对象时，使用空对象，并记一条 Trace（`TraceStage.TOOL`，事件名 `call_args_missing`，带 evidenceId）。不回退到 `argsBrief`：摘要会让本体误判，空对象至少是保守的，本体会按“没有补查过”处理。
- `nodeCalls` 只在配置了 `ToolGuard` 时才会被调用（`GuardInterceptor`），所以不启用本体的部署不会多出这次查询。
- 本卡不改动 `EvidenceHead`，Redis 中不存完整参数。理由是参数可能很大（比如写工具提交的正文），每次 flush 都会重写证据头，会挤占 flush 体积。

### 2.3 分层

`EvidenceReader` 在 `engine.tools.guard`，不直接依赖仓储。`RuntimeStateFacade` 新增一个方法，由 `DefaultRuntimeStateFacade` 调用 `CallRepository` 实现：

```java
/**
 * 按证据 id 批量取工具调用的完整入参（tool_call_record.request）。
 *
 * @param turn        本轮视图
 * @param evidenceIds 证据 id
 * @return evidenceId → 入参 JSON；查不到的 id 不在结果中
 */
Map<String, JsonNode> argsOf(TurnScope turn, Collection<String> evidenceIds);
```

## 3. 代码改动

### 3.1 `EvidenceReader`

1. 新增 `public boolean reachable(ToolExecutionContext ctx, String evidenceId)`：判断 evidenceId 是否在本节点（`ctx.scope().evidence()`）或任一上游节点（`stateFacade.evidenceOf`）的证据中，**不论状态**。
2. `matches`：读报文前先判断 evidenceId 是否在 `successfulEvidenceNewestFirst(ctx)` 的结果中，不在就返回 false。
3. `nodeCalls`：
   - 先取 `successfulEvidenceNewestFirst(ctx)`；
   - 再用 `stateFacade.argsOf(...)` 一次性取完整参数；
   - 组装 `Call` 时用完整参数；缺失时用空对象，并记 Trace；
   - 删除 `argsOf(String argsBrief)`；
   - 更新方法 Javadoc（现在写的是“入参取证据头 argsBrief”）。

### 3.2 `NodeBuiltinTools.evidenceGet`

- 构造器注入 `EvidenceReader`。
- 非空检查之后、读报文之前，调用 `evidenceReader.reachable(ctx, id)`，不可达就按 2.1 拒绝。
- 工具描述补一句：“只能读取本节点及上游节点的证据”。

### 3.3 `RuntimeStateFacade` / `DefaultRuntimeStateFacade`

新增 `argsOf`（见 2.3）。实现：`calls.requests(taskId, evidenceIds)`，把结果解析为 `JsonNode`；没有活动任务或 id 为空时返回空 Map。

### 3.4 `CallRepository` 及数据访问层

```java
/**
 * 按调用 id 批量取完整入参（只查 call_id、request 两列）。
 *
 * @return callId → request JSON 文本；没有的 id 不在结果中
 */
public Map<String, String> requests(String taskId, Collection<String> callIds)
```

按 `TaskInstanceDao.advanceStateVersion` 的现有写法，逐层新增一条查询：
- `ToolCallRecordMapper.xml`：新增 `selectRequests`，`SELECT call_id, request FROM tool_call_record WHERE task_id = ? AND call_id IN (...)`，不显式写 `organ_id` 条件（由租户拦截器注入，与其他查询一致）；
- `ToolCallRecordDao`、`ToolCallRecordService`、`ToolCallRecordServiceImpl` 各加一个对应方法。

只新增，不改生成的其他语句。`callIds` 为空时直接返回空 Map，不执行查询。

## 4. 不在本卡：D-4

节点历史（`NodeHistory`）中 ToolCall 的参数用的是 `argsBrief`。截短后的值可能被模型照抄。可选的口径有：
1. 保持摘要，在历史中标明“参数为摘要”；
2. 改为“只脱敏、不截短”的完整参数（需要从 MySQL 取，或在证据头中存储）。

两种口径对提示词长度和 flush 体积的影响不同，需要你另行决定。本卡不改历史。

## 5. 文档同步

| 位置 | 修改 |
|---|---|
| CONTRACT_SPEC 来源定义 `EVIDENCE` | 补充：“`builtin_evidence_get` 与来源核验都按此范围校验；越界的 evidenceId 会被拒绝” |
| STATE_SPEC 5.6 | 补充：“`builtin_evidence_get` 只能读本节点和上游节点的证据” |
| SPI 相关说明（若 `docs` 中有 `Call.arguments` 的说明） | 改为“完整入参（`tool_call_record.request`）；查不到时为空对象” |

---

## 6. 任务卡（交给 Cursor）

**卡片 B1：证据读取范围与前序调用参数**

**前提**：先读本文第 2、3 节，CONTRACT_SPEC 来源定义，STATE_SPEC 5.6，以及 `docs/development/code-conventions.md` 的“编码细则”。只改本文开头表格“涉及类”中列出的类和文档；不改 SPI，也不改 `EvidenceHead`。

**步骤**：

1. 按第 3 节改代码。
2. 更新 `EvidenceReaderCallsTest`：其中依赖 `argsBrief` 的断言，改为断言完整参数。
3. 补齐下表的用例。
4. 按第 5 节同步文档。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| E1 | `evidenceGet`：本节点的证据 | 返回报文 |
| E2 | `evidenceGet`：上游节点的证据 | 返回报文 |
| E3 | `evidenceGet`：同任务中非上游节点（兄弟节点）的证据 | 抛 `ToolRejectedException`（`EVIDENCE_PATH_INVALID`），**`payload` 未被调用** |
| E4 | `evidenceGet`：不属于本任务的 id | 同 E3 |
| E5 | `evidenceGet`：本节点失败状态的证据 | 返回报文（任意状态均可读） |
| E6 | `matches`：不可达的 evidenceId | 返回 false，`payload` 未被调用 |
| E7 | `matches`：本节点失败状态的证据 | 返回 false，`payload` 未被调用 |
| E8 | `nodeCalls`：参数中含敏感字段和超长字符串 | `Call.arguments` 与 `request` 完全一致（未脱敏、未截短） |
| E9 | `nodeCalls`：某条证据查不到 `request` | 该条 `arguments` 为空对象，其余正常 |
| E10 | `nodeCalls`：本节点和上游共 3 条成功证据 | `argsOf` 只被调用一次，参数包含全部 3 个 id |
| E11 | `CallRepository.requests`：`callIds` 为空 | 返回空 Map，Service 未被调用 |

**验收**：E1～E11 全部通过；原有测试仍然通过；IDEA 无新增告警；文档已同步。
