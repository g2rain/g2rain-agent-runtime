# 多场景联调修正

| 项 | 内容 |
|---|---|
| 状态 | 已实施，待编译联调 |
| 涉及项目 | g2rain-agent-runtime、g2rain-infra（文案） |
| 关联文档 | [落库核对与执行修正](state-audit-fixes.md)、[节点写操作范围约束](node-write-scope.md)、[工具结果与进展判定](../TOOL_OBSERVATION.md)、[意图路由](../INTENT_ROUTING.md) |

---

## 1. 背景

2026-11-07 CMS 多场景联调（第五轮）：合并文章、条件创建、打标签、跨技能查询、追问补参、发布与撤回、删除、待审批时打断。通过的场景不再列出，问题如下。

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| P1 | 「测试空间2有几篇文章」任务失败，同会话再查仍失败 | `EvidenceFlow.clipEntities` 按字符数截断实体 JSON（`evidenceEntitiesMaxChars=2048`），5 篇带正文的文章被截成非法 JSON：`tool_call_record.entities` 写库报 Invalid JSON，persist 解析失败 | §2 |
| P2 | 「不存在就创建，然后合并」第二次执行（已存在）任务 FAILED | 条件创建节点有 target，`DefaultFinishPolicy` 要求 target 写成功才能 finish，已存在时只能 fail | §3 |
| P3 | 建栏目前规划「确认空间存在」「确认站点存在」两个节点，各弹一次无意义的带 id 更新 | 同 P2：为满足 target 只能写一次 | §3 |
| P4 | 有待审批卡片时说「算了，不撤了」「先别改了，问个别的」，都重弹同一张卡 | 意图模型判 CONTINUE_TASK（其 goalSummary 写的却是「取消并查询」），写待确认时被校正为 UNCERTAIN | §4 |
| P5 | 「打上已有的『运维』标签」查询为空后直接申请新建（L12 回归） | 只靠 ReactPrompt 一句约束 | 观察 |
| P6 | 审批明细：`tagIds：[23444,23428]`；同一篇文章有时显示名称、有时 `文章 ID：23871` | 数组参数契约键为 `tagIds[]`，按 `tagIds` 查不到 ArgSpec；`mergeSeeds` 用无展示名的种子（save 只回主键）把已有名称覆盖成 id | §5 |
| P7 | 最终回复出现「ID: 23285」 | 只靠提示词约束 | 观察 |
| P8 | 有多个空间待选时追问被 `FOLLOW_UP_UNGROUNDED` 拒一次，多跑一轮 | 不带待补参数的追问只在「最近一次查询为空或失败」后放行 | §6 |

CMS 栏目编码、路径可重复保存，属 CMS 校验缺失，不在本文范围。

## 2. 证据实体不截断（P1）

去掉 `EvidenceFlow.clipEntities` 与配置 `runtime.state.evidence-entities-max-chars`，证据实体原样保存。

- `tool_call_record.entities` 是 JSON 列，长度不是问题。
- 实体属性不进提示词：前情投影 `PriorEntityView` 只有 key、displayName、taskId。
- 冷恢复重建节点历史读 `tool_call_record.response`，与 entities 无关。
- 总量已有两道条数上限：会话实体 `entitiesMax`、前情 `priorEntitiesMax`。

## 3. 节点跳过（P2、P3）

### 3.1 问题

「不存在才创建」「存在才更新」是通用的条件写。规划把它拆成独立节点是合理的，缺的是节点「本节点的写操作不需要做」这个出口：有 target 的节点只能写成功后 finish，或 fail。

### 3.2 方案

新增控流动作 `builtin_node_skip(reason)`，节点以 `SKIPPED` 结束。

**准入**（runtime 裁决，不满足时拒绝并给出下一步，错误码 `SKIP_UNGROUNDED` 42250）：

1. 节点有 target；
2. target 尚未写成功（已写成功应 finish）；
3. 本节点或上游节点有一次成功且非空的查询证据，作为「不需要写」的依据。

**可见性**：`NodeToolset` 只给有 target 且非认领（`targetClaimed=false`）的节点提供；与 `builtin_node_approval` 一样按需出现，不干扰其他节点。

**结束语义**：

| 处 | 处理 |
|---|---|
| `ToolExecutionContext` | 新增 `applySkip(reason)`：状态 SKIPPED，reason 作为产出文本 |
| `NodeStatus.loopEvent` / `NodeEvent` | 新增 `LOOP_SKIPPED`；状态机迁到 SKIPPED，不停图 |
| `DefaultNodeRunner` | SKIPPED 与 DONE 一样写 `result`（即 reason）；执行步骤以 reason 收尾，如「已跳过：《智能体会话授权说明》已存在」 |
| 调度与收口 | 无需改：`unlocksDownstream()` 已含 SKIPPED，全部 DONE/SKIPPED 时任务 SUCCEEDED |
| 上游证据 | 无需改：下游按依赖边读上游证据，不看节点状态，合并节点照常取到已存在文章 |
| 拒绝回执的「已完成写步骤」 | 只列 DONE 且有 target 的节点，跳过的不列 |
| GoalGate | 照常核验；`GoalGatePrompt` 补充：SKIPPED 且有 result 表示经查询确认不需要执行，带条件的要求视为已满足，无条件要求的写操作被跳过视为未达成。任务收尾时从未开始的节点也记 SKIPPED，但没有 result，不受影响 |

**提示**：`FINISH_NEED_TARGET` 补一句「查询结果表明该写操作不需要执行时调用 builtin_node_skip 并说明原因」；`ReactPrompt` 结束节点规则补一条跳过（提供该工具时）。

### 3.3 不做

- 不改规划；不改 target 含义。
- 已有的 `SKIPPED` 用法（共享值已存在的 provider 节点、任务收尾时从未开始的节点）不变。

## 4. 待确认写操作时的意图（P4）

### 4.1 方案

新增场景 `WRITE_PENDING`：有进行中的任务且断点含待确认写操作（现 `writePending` 判定不变）。规则单列，打断类在前：

```
WRITE_PENDING（有待确认的操作，见 activeTask）：
- 不执行待确认的操作，也没有提出别的事项 → CANCEL_TASK
- 不执行待确认的操作，改办别的事（包括提出新的问题或要求）→ REPLACE_TASK
- 明确要求换一个数字员工 → SWITCH_AGENT（agentLocked=true 时不可用）
- 同意执行、回答确认问题，或要求修改待确认的操作 → CONTINUE_TASK
- 与任务无关的寒暄、致谢、闲聊 → REPLY_ONLY
```

- 可选模式与 ACTIVE_TASK 相同，兜底仍为 CONTINUE_TASK；`settle` 不变：判为 CONTINUE_TASK 时仍校正为 UNCERTAIN，写操作只由审批卡确认。
- CANCEL_TASK、REPLACE_TASK 走现有 `abandonPrevious`：消费断点、原任务 CANCELED。
- `TurnScene.of` 增加 `writePending` 入参。

### 4.2 不做

- 不在打字时代替审批卡执行写操作。

## 5. 审批明细（P6）

1. **数组参数**：按字段名查不到 ArgSpec 时再按 `字段名[]` 查。引用类数组逐个元素换成名称，以「、」连接；找不到名称的元素不展示，全部找不到时整项不展示。
2. **名称被覆盖**：`EntityExtractor.mergeSeeds` 更新已有实体时，种子没有展示名则保留原名称；新实体仍以 id 兜底。

## 6. 多条候选时可追问（P8）

`requireGrounded` 不带待补参数时，除「最近一次业务调用为空或失败」外，最近一次成功查询有抽取项返回多条（`extractCounts` 任一值大于 1）也放行，供用户从候选中选择。

## 7. 观察项

- P5：用户指明「已有」而查询为空时直接新建。审批卡可拦住误写；难以做成通用规则，继续观察。
- P7：最终回复带内部主键。收尾替换易误伤，继续观察。
- 更新类写操作被当作新增拦截：`post_article_save` 的 `spaceId`、`categoryId` 在契约里无条件必填（`ArgSpec.isRequired` 默认 true），而 CMS 只在新增时必填（`CreateGroup`）。发布、改标题时模型只传 id 与 status，会被 `PARAM_PROVENANCE_CALL_TOOL` 拦 2～3 次、补查空间与分类，每次多 3～4 轮模型调用、约 40 秒。讨论过的方案（契约支持「条件必填」`requiredWithout`、只改契约为非必填、runtime 自动调用查询工具）各有代价，暂不处理，视客户使用反馈再定。

## 8. 文案（g2rain-infra）

文件末尾单独 INSERT，zh / en / ja：

| 键 | 用途 |
|---|---|
| `agent_runtime.42250` | SKIP_UNGROUNDED：跳过没有依据 |
| `step.skip` | 执行过程中的「跳过」步骤名 |

## 9. 实施步骤

1. P1 去掉截断与配置项。
2. P6、P8。
3. P2、P3 节点跳过；infra 文案。
4. P4 场景与提示词；单元测试同步。
5. 编译联调：重跑第五轮场景。

## 10. 工具调用失败日志（重测后补充）

重测时，追问准入规则正确拦下了一次口头确认，但 `DefaultToolInvoker.doInvoke` 对所有异常一律 `log.error` 带完整堆栈，生产环境会产生大量噪音并误触发告警。改为按异常性质记录：

| 情况 | 记录 |
|---|---|
| 内置工具的规则拒绝（`ToolRejectedException`） | 不单独记录：`[Trace][TOOL] invoked ... outcome=REJECTED errorCode=...` 已记工具、错误码与参数 |
| 业务失败（`BusinessException`，且异常链上没有超时或连接失败） | WARN：工具名、错误码、消息，不带堆栈 |
| 其余（超时、连接失败、IO、MCP RPC 错误、未知运行时异常） | ERROR，带堆栈 |

只改日志级别，观测分类与返回给模型的内容不变。
