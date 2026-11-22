# 第二批 B2：节点与图（P2-1、P2-3、P2-7、P2-10、P2-11）

| 项 | 内容 |
|---|---|
| 状态 | 已实现（按 2.2 推荐方案：三个新错误码） |
| 修复条目 | 扶正清单 P2-1、P2-3、P2-7、P2-10、P2-11 |
| 涉及类 | `DefaultTurnScope`、`RuntimeStatePersistence`、新增 `ResumeMark`（P2-1）；`ReActLoopResult`、`DefaultReActLoop`、`DefaultNodeRunner`、`RuntimeErrorCode`（P2-3）；`NodeFinisher`、`EntityExtractor`（P2-7）；`ControlBuiltinTools`（P2-10）；`TaskGraph`、`DefaultGraphKernel`、`RuntimeMessage`（P2-11） |
| 跨项目 | g2rain-server：`g2rain-infra/scripts/g2rain-infra.sql` 追加 i18n 行，并同步到 `g2rain-bootstrap/config/mysql/` 与 `g2rain-deploy/config/mysql/` 下的同名文件 |
| 不涉及 | 状态层（`TurnSessionHydrate`、`TurnFlushPlan`）、表结构 |
| 关联文档 | [状态模型](../STATE_SPEC.md) 5.3、5.6、5.11；[契约规范](../CONTRACT_SPEC.md) 内置工具表；[落库核对](state-audit-fixes.md) D4、4.12 |

---

## 1. P2-1：换任务时旧断点没有被消费

### 问题

恢复审计用的三个字段 `lastResumeAction`、`lastResumeMessageId`、`lastResumeAt` 是**轮级**的，不记录属于哪个任务。persist 时按**当前任务**的 ID 调用 `consumeCheckpoint`。

在 `REPLACE_TASK` / `SWITCH_AGENT` 时，`DefaultTaskLifecycle.prepare` 先对旧任务执行 `resume(CANCEL)`，再 `close`，然后 `start` 新任务。到了 persist，用的是新任务的 ID，查不到断点就直接返回。结果是：

- 旧任务的断点没有逻辑删除；
- 也没有写恢复记录。

旧任务已经是终态，不会再被读取，所以只影响审计。但这违反了 STATE_SPEC 5.3“保证每次恢复都有记录”。

### 设计

- 新增 record `ResumeMark(String taskId, ResumeAction action, String messageId, long actedAt)`，放在 `domain.state.task`。
- `DefaultTurnScope`：
  - 三个字段合并为 `private @Nullable ResumeMark resumeMark`；
  - `resume(...)` 在消费断点时，记下**当前任务的 ID**；
  - `start()` **不重置** `resumeMark`；
  - 原有的三个 getter 合并为 `Optional<ResumeMark> resumeMark()`。
- `RuntimeStatePersistence.persistTurn`：`consumeCheckpoint` 移到当前任务的块之外，按 `mark.taskId()` 调用。位置在写完被顶替的旧任务之后，这时旧任务的断点行还在。

## 2. P2-3：节点的 `errorCode` 写的是状态名

### 问题

`DefaultNodeRunner.proceed` 在节点失败或取消时，`errorCode` 写的是 `node.getStatus().name()`，也就是 `FAILED` / `CANCELLED`，真实原因丢失了。

- state-audit-fixes 4.12 定过：**取消不是失败，`error_code` 为空**；
- D4 要求失败节点写节点错误码。

### 2.1 失败来源

节点失败的来源只有三种，目前都没有对应的错误码：

| 来源 | 位置 | 现有文案 |
|---|---|---|
| 轮次用尽 | `DefaultReActLoop`（`MAX_ROUNDS`） | `RuntimeMessage.NODE_ROUNDS_EXHAUSTED` |
| 连续无进展 | `DefaultReActLoop.stalledFail` | `RuntimeMessage.NODE_STALLED` |
| 模型调用 `builtin_node_fail` | `DefaultReActLoop.exited` | 模型给出的原因 |

### 2.2 方案（需拍板）

**推荐**：在 `RuntimeErrorCode` 中新增三个错误码，并在 Infra 补齐三种语言的文案：

| 枚举 | code | 中文 | 英文 | 日文 |
|---|---|---|---|---|
| `NODE_ROUNDS_EXHAUSTED` | `agent_runtime.42256` | 处理轮次已用完：{0:detail} | Processing rounds exhausted: {0:detail} | 処理回数の上限に達しました：{0:detail} |
| `NODE_STALLED` | `agent_runtime.42257` | 连续多轮没有进展：{0:detail} | No progress over several rounds: {0:detail} | 複数回進展がありません：{0:detail} |
| `NODE_DECLARED_FAILED` | `agent_runtime.42258` | 节点声明失败：{0:detail} | Step declared failed: {0:detail} | ステップが失敗を宣言しました：{0:detail} |

备选：不新增错误码，失败时 `errorCode` 留空，只保留文案。这样改动小，但前端和观测都分不出失败原因。

### 2.3 改动（按推荐方案）

- `ReActLoopResult` 新增 `@Nullable String errorCode`，并更新各构造入口：
  - 轮次用尽 → `NODE_ROUNDS_EXHAUSTED.getCode()`；
  - 无进展 → `NODE_STALLED.getCode()`；
  - `exited` 且状态为 `FAILED` → `NODE_DECLARED_FAILED.getCode()`；
  - 其他 → `null`。
- `DefaultNodeRunner.proceed`：
  - `FAILED` 时，`errorCode` 取 `nodeResult.errorCode()`；
  - `CANCELLED` 时，`errorCode` 为 `null`；
  - `errorMessage` 的写法不变。
- Infra：在 `g2rain-infra.sql` **文件末尾**追加一条独立的 `INSERT`，接着现在最大的 id（6810）往后编号，共 9 行（3 个错误码 × 3 种语言，`message_usage_code = 'ERROR_CODE'`），格式照抄文件末尾 `agent_runtime.42255` 那一段。然后把同一个文件同步到 bootstrap 和 deploy 两处。

## 3. P2-7：`NodeFinisher` 先置 DONE 再写共享值

### 问题

`finish` 先执行 `applyFinish`（节点置为 DONE），再执行 `captureOutputs`（写共享值）。`captureOutputs` 遇到与平台字段同名的输出键时，会抛 `PLATFORM_FIELD_FORBIDDEN`。这个异常不是 `ToolRejectedException`，结果是：节点已经是 DONE，工具观测却是失败；而且前面的共享值可能已经写了一部分。

### 设计

先校验，再一次性写入：

- `EntityExtractor` 新增 `public void requireShareable(@Nullable SkillContract contract)`：把 `share()` 中的平台字段检查提前到这里，遍历所有带 `share` 的输出，有冲突就抛 `PLATFORM_FIELD_FORBIDDEN`。
- `share()` 中删除这项检查。
- `NodeFinisher.finish` 的顺序改为：完成判定 → `requireShareable` → `applyFinish` → `captureOutputs`。

## 4. P2-10：`builtin_node_skip` 不检查 `LOOKUP_PENDING`

- `ControlBuiltinTools.skip`：在 `requireSkippable` 之前调用 `HaltResolver.requireNoLookup(ctx)`，与 finish、fail、follow_up 一致。
- CONTRACT_SPEC 内置工具表补一行 `builtin_node_skip`：参数 `reason`；只有节点有完成目标、目标工具尚未成功、本节点已有非空的成功读查询时才可调用；有补查待完成时拒绝（`LOOKUP_PENDING`）；调用后节点 `SKIPPED`。

## 5. P2-11：`TaskGraph.resumeAwaiting` 绕过 `mark`

### 问题

委派链损坏时，`resumeAwaiting` 直接 `setStatus(FAILED)`，错误码和文案都是字面量（`"DELEGATE_BROKEN"`、`"委派链损坏：前置节点缺失"`）。它绕过了 `turn.mark`，所以 `finishedAt` 不会写。

### 设计

- `TaskGraph.resumeAwaiting()` 改为返回 `List<String>`（委派链损坏的父节点 ID），自身**不再**修改这些节点的状态；子节点都已终态的父节点，照常回到 READY。
- `nextWave` 不再调用 `resumeAwaiting`。
- `DefaultGraphKernel` 在调用 `nextWave` 之前先调用 `resumeAwaiting()`，对返回的每个 ID 执行 `turn.mark(id, FAILED, RuntimeErrorCode.DELEGATE_BROKEN.getCode(), RuntimeMessage.NODE_DELEGATE_BROKEN.text())`。
- `RuntimeMessage` 新增 `NODE_DELEGATE_BROKEN("node.delegate_broken", "委派链损坏：委派的前置节点缺失。")`，Infra 同样追加 3 行（`message_usage_code = 'UI_MESSAGE'`，`tag = 'AGENT_RUNTIME'`，格式照抄 `node.stalled`）。
- 更新 `TaskGraph` 类注释和 `nextWave` 的注释。

## 6. 文档同步

| 位置 | 修改 |
|---|---|
| STATE_SPEC 5.3 | 写明恢复记录按“被恢复的任务”写入，换任务时也会写 |
| STATE_SPEC 5.11 | 失败节点的 `errorCode` 取值（三个新错误码、委派链损坏）；取消节点 `errorCode` 为空 |
| CONTRACT_SPEC 内置工具表 | 补 `builtin_node_skip` |
| CONTRACT_SPEC 第 15 节（委派） | 委派链损坏时，父节点 `FAILED`，错误码 `DELEGATE_BROKEN` |

---

## 7. 任务卡（交给 Cursor）

**卡片 B2：节点与图**

**前提**：先读本文全文，以及 `docs/development/code-conventions.md` 的“编码细则”。只改本文开头表格中列出的类、Infra SQL 和文档。

**步骤**：

1. 按第 1～5 节改代码。
2. Infra SQL：在 `g2rain-server/g2rain-infra/scripts/g2rain-infra.sql` 末尾追加 12 行（9 行 `ERROR_CODE`、3 行 `UI_MESSAGE`），**不改已有行、不调整已有 id**；再把整个文件同步到 `g2rain-server/g2rain-bootstrap/config/mysql/g2rain-infra.sql` 和 `g2rain-server/g2rain-deploy/config/mysql/g2rain-infra.sql`。
3. 补齐下表的用例；更新已有测试中依赖 `nextWave` 自动回醒、或依赖三个恢复字段的部分。
4. 按第 6 节同步文档。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| N1 | 旧任务挂起，本轮 `REPLACE_TASK`（`resume(CANCEL)` → `close` → `start`），再 `persistTurn` | `consumeCheckpoint` 收到的是**旧任务的 ID** |
| N2 | 普通确认续跑后 `persistTurn` | `consumeCheckpoint` 收到当前任务的 ID、动作、消息 ID |
| N3 | 本轮没有恢复 | `consumeCheckpoint` 未被调用 |
| N4 | 轮次用尽 | 节点 `FAILED`，`errorCode = agent_runtime.42256` |
| N5 | 连续无进展 | `errorCode = agent_runtime.42257` |
| N6 | 模型调用 `builtin_node_fail` | `errorCode = agent_runtime.42258`，`errorMessage` 为模型给出的原因 |
| N7 | 节点被取消 | `errorCode` 为空 |
| N8 | `finish`：契约中有一个与平台字段同名、带 `share` 的输出 | 抛 `PLATFORM_FIELD_FORBIDDEN`；节点状态**不是** DONE；共享值一条都没写 |
| N9 | `finish` 正常 | 节点 DONE，共享值写入（回归） |
| N10 | `skip`：有补查待完成 | 抛 `ToolRejectedException`（`LOOKUP_PENDING`），节点不是 SKIPPED |
| N11 | `resumeAwaiting`：委派边指向缺失节点 | 返回该父节点 ID，父节点状态未被修改 |
| N12 | `DefaultGraphKernel`：存在委派链损坏的父节点 | 父节点经 `turn.mark` 变为 `FAILED`，错误码 `DELEGATE_BROKEN` 的 code，`finishedAt` 非空 |
| N13 | `resumeAwaiting`：子节点都已终态 | 父节点回到 READY，返回空列表（回归） |

**验收**：N1～N13 全部通过；原有测试仍然通过；IDEA 无新增告警；Infra SQL 三处内容一致；文档已同步。
