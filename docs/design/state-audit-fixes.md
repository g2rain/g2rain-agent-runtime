# 落库核对与执行修正

| 项 | 内容 |
|---|---|
| 状态 | 已实施，四轮联调通过；单元测试待补 |
| 涉及项目 | g2rain-agent-runtime、g2rain-agent-app、g2rain-agent-studio（技能手册） |
| 关联文档 | [状态模型](../STATE_SPEC.md)、[执行过程与失败透传](progress-and-failure.md)、[节点写操作范围约束](node-write-scope.md) |

---

## 1. 背景

2026-11-07 在本地 chat 做了三轮 CMS 测试（会话 `acc0503b…`、`f6314553…`），导出 runtime 库 10 张表（`scripts/123.sql`），对照 runtime / model-nexus 日志逐字段核对。结论：多数「字段不对」不是写坏，而是写入路径未被使用或 runtime 逻辑问题；另有若干确定的落库缺陷。本文汇总全部结论与修正方案。

## 2. 落库缺陷（直接修）

| # | 现象 | 根因 | 修正 |
|---|---|---|---|
| D1 | `task_graph_node.state.tokens` 恒为 0 | `DefaultReActLoop`、`DefaultNodeRunner` 均调用 `scope().round(0)` | `NodeScope.round(int)` 拆为 `round()`（推进轮次）与 `spend(long)`（累加 token）；ReAct 每轮取响应 `usage.totalTokens` 累加 |
| D2 | 任务终态后未执行节点停在 `PENDING` / `READY` | `DefaultTurnScope.close` 只改任务 | `close` 收尾：未开始（`PENDING`、`READY`）→ `SKIPPED`；已开始未终态（`RUNNING`、`SUSPENDED`、`WAITING_DELEGATE`）→ `CANCELLED` |
| D3 | 首轮失败的节点 `error_code`、`error_message` 为空 | `TaskGraphNodeConverter.node2dto` 忽略了两字段，新节点整行插入时丢失 | 去掉两个 `ignore` |
| D4 | `chat_progress` 的 FAILED 步骤 `error_code` 全空 | `DefaultNodeRunner.finishStep` 传 `null`；`TurnProgress.finish` 只给目标步骤写错误码，被连带结束的子步骤（ROUND）没有 | `finishStep` 传节点错误码；连带结束的子步骤同样写入 |
| D5 | `task_resume_record.acted_at` 为落库时间 | `TaskRepository.consumeCheckpoint` 用 `Moments.now()`，在轮末执行 | `DefaultTurnScope.resume` 记下动作时间，持久化时写入 |
| D6 | `task_checkpoint.pending_action.args`、`chat_progress.extra.args` 二次编码 | `PendingAction.args` 为字符串；`ToolEvents` 直接放参数字符串 | `PendingAction.args` 改为 `JsonNode`；`ToolEvents` 放解析后的 JSON |
| D7 | `chat_progress` 中 TOOL 步骤 `round` 为空 | `ToolEvents.started` 传 `null` | 取节点当前轮次 |

核实后不改：`task_checkpoint.expected_fields` 出现 `categoryName`。追问校验（`ControlBuiltinTools.askable`）按本技能全部工具参数匹配，`categoryName` 是分类查询工具的参数，校验结果正确。

## 3. 字段调整

| 字段 | 处理 | 说明 |
|---|---|---|
| `task_graph_node.inputs`、`outputs` | 删除 | runtime 只写不读；冷恢复读 `state`（完整 `NodeSpace`，已含两者）。agent-app 任务追踪改读 `state.inputs` / `state.outputs` |
| `task_resume_record.payload` | 删除 | 从未写入；REPLY 内容经 `message_id` 关联 |
| `task_instance.skill_versions` | 保留 | 预留技能多版本 |
| `chat_message.model` | 改语义 | USER 消息：用户所选模型（默认模型为空）；本轮最终 AGENT_REPLY：本轮实际调用的模型（取模型响应 `model`） |
| `chat_message.channel` | 补写 | AGENT 消息写入本轮入站渠道 |
| `tool_call_record.result_summary` | 改注释、去噪 | 它是证据回放给模型的结果简报（`EvidenceHead.resultBrief`）；截断前去掉响应信封字段 `requestId`、`requestTime` |
| 各表 `create_time` 晚于业务时间 | 不改 | 轮末统一刷盘的设计结果；业务时间见 `started_time`、`created_at_ms`、`acted_at` |

## 4. 执行逻辑修正

### 4.1 规划漏边
`PlanGraphs.materialize`：非首个、且 `dependsOn` 为空的步骤，接到前一步之后。当前图为单节点调度（`TaskGraph.nextWave` 取一个即 `break`），补边不改变执行顺序。日后开启同级并行时需重新评估此规则。

### 4.2 恢复动作只作用于焦点节点
`frame.action()` 是本轮级别，同一轮后续执行的节点也会读到（日志 `node_start … action=CONFIRM`）。`DefaultNodeRunner` 中确认派发、VERIFY 恢复、日志只对恢复检查点所在节点生效，其余节点按普通启动。为此 `TaskCheckpoint` 新增 `nodeId`（建检查点时写入，`task_checkpoint.node_id` 改按它落库）。现状未造成重复派发（派发后已清 pending），属于防御性修正。

### 4.3 prior 携带原始诉求与被拒动作
`TaskRecord` 新增：
- `request`：开任务时的用户原文，截断至 300 字；
- `rejected`：以拒绝结束的任务，记被拒工具名与参数。参数按契约脱敏、去掉平台注入参数、**不截断**（联调发现截断会让沿用的正文缺字）；在 `DefaultGraphTransitionReducer.closeByAction` 拒绝时写入 `TaskMetadata.rejected`，结束时进入记录。

`PriorTaskView` 同步投影。三处 `new TaskRecord(...)` 收敛为 `TaskRecords.of`。

### 4.4 节点看到全局计划
新增投影块 `<task_plan>`：任务内全部节点的 `title`、`status`，当前节点标 `current`。`ReactPrompt` 输入说明补充：`userGoal` 中由计划内其他步骤负责的部分不在本节点处理，已完成步骤的结果见 `<upstream_evidence>`。

### 4.5 校验结论入任务记录
`DefaultGoalGate` 的核验结论写入 `TaskMetadata.goalMet` 并进入 `TaskRecord`，不改变任务状态。`GoalGatePrompt` 补充：查询结果为空且已如实告知，视为达成。

### 4.6 对外文案不暴露内部标识
`ReactPrompt` 结束节点规则补充：给用户的文字不出现平台注入字段及其值（如 organId）和内部主键。

### 4.7 文章挂栏目（技能手册）
CMS 中栏目通过 `categoryId` 挂载分类，文章属于分类。`cms.article.lifecycle` 手册补充：用户要求在某栏目下发文章时，从上游结果或前情取该栏目挂载的分类，按该分类创建；取不到时追问确认分类。文章技能未绑定栏目查询工具，本次不新增绑定。

### 4.8 任务图节点顺序（第二轮联调）
热态以 Redis Hash 存图，`HGETALL` 无序，`HashCodec.graphOf` 恢复后节点顺序被打乱；单节点调度下多个 READY 节点的执行顺序因此随机，拒绝回执的「已完成」列表也乱序。修正：
- 热态 Hash 新增 `_order` 字段记节点顺序，恢复时按它排列；
- 冷恢复 `GraphRepository.load` 按主键升序（主键按规划顺序生成；查询默认按主键倒序）。

### 4.9 审批明细按契约渲染（第二轮联调）
审批是用户授权，明细须让用户看清批准的内容。原实现复用给模型的参数简报（按 `briefMaxChars` 截断），正文被截断、引用参数显示为内部主键。改为按契约渲染，不区分技能：
- 参数脱敏后不截断；平台注入参数不展示（不变）；
- 只能取自查询结果或前情的引用参数（`sources` 不含 USER、MODEL），按会话实体（`type:id`）换成对象名称展示，找不到名称不展示。
- 会话实体在轮末才并入，同一轮刚创建的对象另从本轮已打开节点的成功证据实体中取名称（第三轮联调发现同轮新建的分类显示不出名称）。

### 4.10 进度轮次统一按节点累计（第二轮联调）
ROUND 步骤原按本轮循环序号（每轮重新从 1 数），消息与工具调用记录按节点累计，两套计数不一致；确认后派发的写调用在轮次之外，`round` 为空。统一为节点累计轮次：ROUND 步骤与 TOOL 步骤都取 `scope.rounds()`。

### 4.11 会话实体不存平台字段（第四轮联调）
实体属性取自工具返回，会带上 `organId` 等平台字段，并随 prior 投影给后续任务。`EntityExtractor` 合并实体时剔除与 `PlatformCatalog.keys()` 同名的属性，与 shared 禁写平台字段同一依据。

### 4.12 不在 runtime 处理的
- 「发」是否等于发布属业务语义，只改 `cms.article.lifecycle` 手册：「发、发一篇、写一篇」按保存草稿处理，明确说「发布、上线」才发布。
- 查询为空时建议用户创建：画像已约束，属模型遵循问题，先观察。
- 被取消、拒绝的节点 `error_code` 为空：取消不是失败，维持现状。

## 5. 表结构变更

```sql
ALTER TABLE task_graph_node DROP COLUMN inputs, DROP COLUMN outputs;
ALTER TABLE task_resume_record DROP COLUMN payload;
```

PO / DTO / VO / SelectDto / Mapper XML 手工删字段，不重新生成。

## 6. 实施步骤

1. 表结构与字段删除（§5、§3），agent-app 任务追踪同步。
2. 落库缺陷 D1–D7。
3. 字段语义：model、channel、result_summary。
4. 执行逻辑 4.1、4.2。
5. 执行逻辑 4.3–4.6，联调。
6. 技能手册 4.7。
