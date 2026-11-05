# 节点写操作范围约束

| 项 | 内容 |
|---|---|
| 状态 | 已实施，2026-11-05 联调通过；单元测试待补 |
| 涉及项目 | g2rain-agent-runtime |
| 关联文档 | [工具结果与进展判定](../TOOL_OBSERVATION.md)、[状态模型](../STATE_SPEC.md)、[链路轨迹日志](../TRACE_LOGGING.md) |

---

## 1. 背景

2026-11-05 CMS 联调第 4 轮：「给这篇文章打上『发布』和『智能体』两个标签」。

规划模型给出 6 个节点，计划本身合理：

| 节点 | objective | dependsOn | target |
|---|---|---|---|
| n1 | 定位需要打标签的文章 | - | 无 |
| n2 | 检查标签「发布」是否已存在 | - | 无 |
| n3 | 检查标签「智能体」是否已存在 | - | 无 |
| n4 | 创建标签「发布」 | n2 | `post_tag_save` |
| n5 | 创建标签「智能体」 | n3 | `post_tag_save` |
| n6 | 为文章添加两个标签 | n1、n4、n5 | `post_article_tag_relation_batch_add_tags` |

实际执行：

1. n1 越界：在自己的 ReAct 环内建了两个标签、完成绑定，然后 DONE。
2. n2、n3 与 n1 无依赖边，按设计看不到 n1 的证据，重新查询，查到标签已存在。
3. n4、n5 拿到「已存在」，改为带 `id` 的 `post_tag_save`，各弹一张「确认标签已存在」的审批卡；「发布」那次真实执行了一次无意义的更新。
4. n6 再次绑定，被 `WriteGateInterceptor` 按已有成功记录复用，没有重复写入。

结果：一轮从约 1 分钟拖到 3 分钟以上，多出 2 张审批卡和 1 次无意义写入。

## 2. 根因

1. **节点照用户原话抢做了别的节点的写操作。** 节点提示词同时投影 `objective` 与 `userGoal`（用户原话），`ReactPrompt` 只要求「本节点只做 objective 范围内的事」。同类问题此前已出现两次：[REFACTOR_PLAN](../REFACTOR_PLAN.md) L21（节点 1 照原话把分类也建了）、L24（只加了提示词约束）。提示词约束不足以兜住。
2. **目标认领让只读节点变成写节点。** `DefaultToolInvoker.invoke` 调用 `ToolExecutionContext.claimTarget`：节点没有 target 时，首个写调用被记为它的目标并放行。日志：`11:15:21 [NODE] target_claimed nodeId=f96376ed…(n1) target=native_g2rain_cms_post_tag_save`，而该工具正是 n4、n5 的 target。
3. **工具层不看计划分工。** `WriteGateInterceptor` 只做确认闸与查重，不判断写操作是否已分给其他节点。

`userGoal` 不能去掉：L8 为保留用户给的细节（如文章正文）才改用原话；L16 依赖它判断多写技能是否还有未完成的写操作。

## 3. 目标与非目标

**目标**

- 规划分给某个节点的写操作，只由该节点执行。
- 判断只依据计划（各节点的 target）与契约中的写工具，不针对任何技能或业务。
- 越界写在弹审批卡之前被拒，模型收到明确的下一步。

**非目标**

- 不限制读工具，也不限制计划未分配的写（如页面节点在空间为空时新建空间）。
- 不改 target 含义、`userGoal` 投影、依赖边与证据可见范围，不加表字段。

## 4. 方案

### 4.1 规则

节点调用写工具时：

1. 工具是本节点的 target：放行。
2. 工具是图中**其他未结束节点**的 target：拒绝，且不认领。
3. 其他情况：放行，照常认领、确认与查重。

### 4.2 实现

| 位置 | 改动 |
|---|---|
| `domain/graph/TaskGraph.targetOwner(toolName, exceptNodeId)` | 新增：返回以该工具为 target、未结束的其他节点 |
| `domain/tool/ToolExecutionContext.claimTarget` | 该工具已有其他未结束节点作为 target 时不认领 |
| `engine/tools/guard/WriteScopeInterceptor` | 新增，`@Order(0)`，按 4.1 判定；排在参数绑定与 `WriteGateInterceptor` 之前，被拒的调用不绑定、不申请确认 |
| `enums/RuntimeErrorCode.WRITE_OUT_OF_SCOPE` | `agent_runtime.42249`：「写操作不在本节点范围内：{0:toolName}」；g2rain-infra 种子已补 zh/en/ja |
| `enums/ModelHint.WRITE_OUT_OF_SCOPE` | 「[{tool}] 是步骤「{node}」的完成目标，由该步骤执行」 |
| `enums/ModelHint.NEXT_FINISH_SCOPE` | 「不要调用该写工具；按 objective 完成本节点后调用 builtin_node_finish」 |
| `docs/TOOL_OBSERVATION.md`、`docs/STATE_SPEC.md`、`docs/index.md` | 拒绝来源、认领说明与本文链接 |

按 [工具结果与进展判定](../TOOL_OBSERVATION.md)，`REJECTED` 计入无进展；模型反复越界时由 `max-no-progress-rounds` 收口。

### 4.3 各类节点

| 节点 | 行为 |
|---|---|
| 单候选单节点 | 图中没有其他节点，不受限 |
| 保底链路 | 各节点通常无 target，不受限 |
| 委派子节点 | 不能写父节点（等待中、未结束）的 target；前置写照常 |
| 前置节点 | 同规则 |

## 5. 预期效果

**本次场景（第 4 轮）**

| 节点 | 现在 | 修复后 |
|---|---|---|
| n1 | 认领 `post_tag_save`，建标签并绑定 | 两次写调用被拒，查到文章后 finish |
| n2、n3 | 查到「已存在」 | 查到「不存在」 |
| n4、n5 | 带 `id` 更新，弹「确认已存在」卡 | 各建一个标签，各弹一张审批卡 |
| n6 | 绑定被复用 | 用上游证据绑定一次 |

多余审批卡与无意义更新消失；审批卡与计划中的写节点一一对应（3 张）。

**附带写**：页面节点在空间为空时新建空间、文章节点在没有分类时新建分类，计划未把该步骤分给其他节点时照常执行。

**同一工具的多个目标**：「新建两篇文章再合并」中 A、B 的 target 都是 `post_article_save`，规则按工具名判断，A 顺手建了 B 那篇时不会被拦。此时 B 再写同一篇，`WriteGateInterceptor` 按幂等键复用 A 的成功结果，不重复写入、不再申请确认（L22），B 补记证据后正常结束（L21）。结果正确，只是执行的节点变了。

## 6. 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| 同一工具多目标时越界 | 见第 5 节 | 由写复用兜底，结果正确 |
| 模型被拒后反复尝试 | 拒绝提示不够清楚时 | 下一步提示明确要求 finish；无进展计数收口 |

## 7. 验证

1. 单元测试：`TaskGraph.targetOwner`（排除自身、跳过已结束节点）；`WriteScopeInterceptor` 覆盖读工具、内置工具、本节点 target、其他未结束节点的 target、无人认领的写五种情况；`claimTarget` 遇到其他节点的 target 不认领。
2. 重跑 CMS 联调第 4 轮，核对 Trace：n1 出现 `WRITE_OUT_OF_SCOPE` 后 DONE，无 n1 的 `target_claimed`；审批卡共 3 张；mcp-registry 中写调用依次为 2 次 `post_tag_save`、1 次 `batch_add_tags`。
3. 回归第 1～3 轮（单技能场景），行为与本次联调一致。

---

## 8. 补充：越界写后的交接收口

| 项 | 内容 |
|---|---|
| 状态 | 已实施，2026-11-05 联调通过；单元测试待补 |
| 来源 | 2026-11-05 联调「查两篇文章，不存在就各建一篇，再合并建第三篇」 |

### 8.1 现象

规划为 4 个节点：查询两篇文章是否存在 → 建「Model Nexus 简介」→ 建「MCP Registry 简介」→ 合并建「平台能力合集」。

1. n1（查询，无 target）查到两篇都不存在后，用 `builtin_node_follow_up` 问用户「是否同意创建这三篇」；追问依据校验因「最近查询为空」放行。
2. 用户回复「同意，请创建」，n1 连续 4 次调用 `post_article_save`，均被 `WRITE_OUT_OF_SCOPE` 拒绝；模型服从用户最新指令，不按「下一步」finish。
3. 4 次 `REJECTED` 计入无进展，`STALLED` → n1 FAILED → 任务 FAILED，后续 3 个节点未执行，回复「连续多轮没有进展，已停止处理。」

修复前 n1 会越界把三篇都建了，任务能成功；现在越界被拦住，节点却没有正常收口的出路。

### 8.2 方案

**规则**：工具调用以 `WRITE_OUT_OF_SCOPE` 被拒时，若本节点已至少成功完成过一次业务调用，runtime 立即替模型尝试完成本节点；`FinishPolicy` 允许则节点 DONE，否则照常进入下一轮。

- 拒绝原因说明这件事归另一个节点；本节点已做过业务调用、且满足结束条件（必填产出齐全、目标已成功、无未收口证据），就已完成自己的部分。
- 「至少成功完成过一次业务调用」是本规则唯一新增的前置条件：CMS 四个技能都声明了非必填产出，`DefaultFinishPolicy` 在这种情况下不要求业务调用；不加这一条，先抢写、后查询的节点会在什么都没做时被收口。
- 结束条件其余部分复用 `NodeFinisher` / `DefaultFinishPolicy`。不满足时拒绝观测照常回给模型，行为与现在一致。
- 与 GoalGate 的关系不变：收尾时 GoalGate 仍按各节点结论与证据核验整体目标。

**节点结论**：新增 `RuntimeMessage.NODE_HANDED_OVER`（`node.handed_over`）：「已完成「{title}」，「{owner}」由后续步骤执行。」，`title` 取本节点标题（空则 objective），`owner` 取目标所属节点标题（空则 nodeId）。

**位置**：`DefaultReActLoop` 中工具观测为非成功、即将记一次无进展处；判断 `observation.errorCode() == WRITE_OUT_OF_SCOPE` 时先调用 `nodeFinisher.finish`，成功即 `exited`。所属节点由 `TaskGraph.targetOwner` 取得。

### 8.3 改动清单

| 文件 | 改动 |
|---|---|
| `engine/flow/react/DefaultReActLoop.java` | 越界拒绝后尝试交接收口 |
| `i18n/RuntimeMessage.java` | 新增 `NODE_HANDED_OVER` |
| g2rain-infra `scripts/g2rain-infra.sql` | `UI_MESSAGE` / `AGENT_RUNTIME` 补 zh、en、ja 三条 `node.handed_over` |
| `docs/TOOL_OBSERVATION.md` | `WRITE_OUT_OF_SCOPE` 说明交接收口 |

### 8.4 预期效果

| 节点 | 现在 | 修复后 |
|---|---|---|
| n1 | 追问 → 用户同意 → 4 次越界写被拒 → FAILED，任务失败 | 追问 → 用户同意 → 第 1 次越界写被拒即 DONE（结论「已完成「查询文章是否存在」，「创建「Model Nexus 简介」草稿」由后续步骤执行。」） |
| n2、n3 | 未执行 | 各建一篇，各一张审批卡 |
| n4 | 未执行 | 读上游两篇正文，合并后建第三篇，一张审批卡 |

打标签场景同样受益：被拒的查询节点不再需要模型自己再走一轮 finish。

n1 向用户追问「是否同意创建」本身不合理，但判定追问内容归属不能做成通用规则；交接收口后它只多问一句，不影响结果。

### 8.5 验证

1. 单元测试：越界拒绝且满足结束条件 → DONE、结论为交接文案；越界拒绝但未完成业务调用 → 不收口、继续下一轮。
2. 重跑本场景：n1 出现一次 `WRITE_OUT_OF_SCOPE` 后 DONE；审批卡 3 张；第三篇正文包含前两篇内容。

---

## 9. 补充：停滞提示带上步骤与工具

| 项 | 内容 |
|---|---|
| 状态 | 已实施，联调中未触发停滞，待验证；单元测试待补 |
| 来源 | 第 8 节同一次联调；用户只看到「连续多轮没有进展，已停止处理。」，反馈给运营后没有线索 |

### 9.1 现状

`DefaultReActLoop.stalledFail()` 不带任何上下文，固定返回 `RuntimeMessage.NODE_STALLED`。停滞判定时 runtime 已知道最后一次无进展的工具与错误码，但没有用上。

### 9.2 方案

**文案**

| 码 | zh-CN | 何时出现 |
|---|---|---|
| `node.stalled`（改） | 「{step}」连续多轮没有进展，已停止处理。 | 始终 |
| `node.stalled_last`（新增） | 最后一次：{tool}未成功（{code}）。 | 最后一次无进展有对应工具时追加；无工具（如模型未调用工具）时省略 |

示例：「查询文章是否存在」连续多轮没有进展，已停止处理。最后一次：创建文章未成功（agent_runtime.42249）。

**字段来源**

| 占位 | 来源 | 说明 |
|---|---|---|
| `{step}` | 节点 `title`，空则 objective | 与执行过程时间线的步骤名一致 |
| `{tool}` | 契约中该工具的 `label`（如「创建文章」），无 label 时退回工具注册名 | 不向用户暴露 `native_g2rain_cms_post_article_save` 这类内部名 |
| `{code}` | 最后一次无进展观测的错误码，如 `agent_runtime.42249` | 不用错误码文案：runtime 无现成的错误码文案解析，且工具业务失败、超时、MCP 报错等错误码的文案只是 `{0:message}`；错误码本身对运营排查足够精确 |

追踪号照常显示，运营据此查 Trace；新文案让其不查日志也能先判断出问题的步骤与工具。

**实现**

- `StallTracker`：`recordStall(tool, errorCode)` 记下最后一次无进展的工具名称（契约 label，缺省注册名）与错误码；无参 `recordStall()` 表示与工具无关；`recordProgress` 清空。
- `DefaultReActLoop.stalledFail(...)`：用节点标题与 `StallTracker` 记录拼出文案。
- `RuntimeMessage`：`NODE_STALLED` 改为带 `{step}`；新增 `NODE_STALLED_LAST`；新增多占位 `text(String... named)`。en 文案以空格开头，便于直接拼接。

### 9.3 国际化

`RuntimeMessage.text()` 先按请求语言查 infra 国际化表（`UI_MESSAGE` / `AGENT_RUNTIME`），代码中的中文只在查不到时兜底，所以必须同步种子：

| 码 | 操作 | g2rain-infra `scripts/g2rain-infra.sql` |
|---|---|---|
| `node.stalled` | 改 zh / en / ja 三条（id 6535～6537） | 加 `{step}` 占位 |
| `node.stalled_last` | 新增 zh / en / ja 三条 | id 取全表最大值之后 |

已导入的库另给 UPDATE / INSERT 同步。

### 9.4 改动清单

| 文件 | 改动 |
|---|---|
| `engine/flow/react/StallTracker.java` | 记录最后一次无进展的工具与错误码 |
| `engine/flow/react/DefaultReActLoop.java` | `stalledFail` 带上下文 |
| `i18n/RuntimeMessage.java` | 改 `NODE_STALLED`，新增 `NODE_STALLED_LAST` |
| g2rain-infra `scripts/g2rain-infra.sql` | 改 3 条、增 3 条 |

### 9.5 验证

1. 单元测试：有工具的停滞 → 两段文案齐全；无工具的停滞 → 只有前半句；工具无 label → 退回注册名。
2. 构造一次停滞（如关闭第 8 节交接收口后重跑本场景），核对 zh 与 en 两种请求语言下的提示。

---

## 10. 补充：唤醒的节点优先执行

| 项 | 内容 |
|---|---|
| 状态 | 已实施，2026-11-05 联调通过；单元测试待补 |
| 来源 | 2026-11-05 联调「建导航栏目「产品中心」+ 建静态页面「关于我们」」 |

### 10.1 现象

规划为两个无依赖节点：n1 建栏目、n2 建页面。

1. n1 先执行，栏目写入被确认闸拦下，弹审批卡，n1 挂起；n2 仍为 READY、未执行。
2. 用户点确认（14:33:45）：`[GRAPH] next step=WakeFocus nodeId=n1 action=CONFIRM`，随后却是 `ExecuteWave nodeIds=[n2]`。
3. n2 执行，因页面路径缺失向用户追问并挂起；n1 那次已确认的写入没有执行。
4. 之后轮到 n1 时挂起记录已在唤醒时清掉，n1 重新调用写工具，又弹出一张相同的栏目审批卡。

结果：2 次写入、4 次用户确认。数据正确：已确认写入只按节点自己的待批记录派发，n2 没有借用 n1 的确认。

### 10.2 根因

`DefaultGraphKernel.decide` 在 `WakeFocus` 之后调用 `TaskGraph.nextWave()`，后者按节点列表顺序取第一个可调度节点，不考虑本轮唤醒的焦点。节点列表从库中按行序还原，n2 排在 n1 前面时就先跑 n2。

同一问题也会出现在追问回复上：用户回答的是焦点节点的追问，若列表里另一个 READY 节点排在前面，本轮会先执行那个节点。

### 10.3 方案

**规则**：取波时，本轮焦点节点（`GraphFrame.currentNodeId()`）若可调度且依赖已满足，优先入波；否则按列表顺序取第一个可调度节点（与现在一致）。

- 焦点在一轮内不变：唤醒的节点执行完（DONE 或再次挂起）后不再可调度，后续照常按顺序调度。
- 委派场景：子节点完成后父节点回到 READY，父节点就是焦点时同样优先，符合预期。
- 只改调度顺序，不改依赖判定、唤醒与挂起逻辑。

**实现**

| 位置 | 改动 |
|---|---|
| `domain/graph/TaskGraph.nextWave(@Nullable String focusNodeId)` | 先判焦点节点：未处于不可调度状态且依赖满足则返回它；否则沿用原循环 |
| `engine/flow/graph/DefaultGraphKernel.decide` | 传入 `frame.currentNodeId()` |
| `docs/STATE_SPEC.md` | 调度说明补「焦点优先」 |

### 10.4 预期效果

| 步骤 | 现在 | 修复后 |
|---|---|---|
| 用户确认栏目 | 执行 n2，n1 的确认丢失 | 先执行 n1，派发已确认的栏目写入，n1 DONE |
| 随后 | n2 追问路径；n1 再弹一次审批卡 | n2 执行，追问路径、确认页面写入 |
| 用户确认次数 | 4 次 | 3 次（栏目、页面路径回复、页面） |

### 10.5 验证

1. 单元测试：焦点 READY 且依赖满足 → 返回焦点；焦点不可调度或依赖未满足 → 返回列表中第一个可调度节点；焦点为空 → 行为不变。
2. 重跑场景 1：确认栏目后 `ExecuteWave` 的节点等于 `WakeFocus` 的节点；栏目审批卡只出现一次。
3. 补测拒绝审批：两个标签各一张卡，确认第一张、拒绝第二张，核对已完成写入保留、后续节点不再执行、回复说明做了什么和没做什么。

---

## 11. 补充：拒绝审批的回复说明已完成的步骤

| 项 | 内容 |
|---|---|
| 状态 | 已实施，2026-11-05 联调通过（zh）；单元测试待补 |
| 来源 | 2026-11-05 补测「给「平台能力合集」打「测试G」「测试H」」，确认第一张、拒绝第二张 |

### 11.1 现象

1. 「测试G」已创建（ID 21564），拒绝「测试H」后 `CloseByAction`，任务 CANCELED；CMS 实际写入只有「测试G」一次，「测试H」与绑定都未执行。行为符合 L2 设计。
2. 回复：「已取消，未执行：创建新标签「测试H」\n标签名称：测试H。如需换个做法，直接告诉我。」
   - 没说已完成的部分：用户看不出「测试G」已建好但未绑定，系统里多了一个未使用的标签。多节点任务中拒绝前常已有写入。
   - 审批卡问题由「摘要 + \n + 参数明细」组成（`DefaultEffectHandler` 拼接），`rejectedMessage` 整段放入 `{action}`，读起来别扭。

### 11.2 方案

`DefaultGraphTransitionReducer.rejectedMessage` 调整：

1. **未执行的操作只取摘要**：审批问题取第一行，不带参数明细。
2. **列出已完成的步骤**：图中状态为 DONE 且有 target 的节点，按列表顺序取标题（空则 objective）。`FinishPolicy` 要求有 target 的节点必须 target 成功才能 DONE，所以这些节点都已完成一次写入。没有时不输出这一段。

**文案**

| 码 | zh-CN | 何时出现 |
|---|---|---|
| `reply.rejected_done`（新增） | 已完成：{steps}。 | 有已完成的写步骤时，放在 `reply.rejected` 前 |
| `reply.rejected`（不改） | 已取消，未执行：{action}。如需换个做法，直接告诉我。 | 始终 |

`{steps}` 为各步骤标题，以「；」连接（步骤标题本身来自规划，与用户输入同语种）。

示例：已完成：创建标签「测试G」。已取消，未执行：创建新标签「测试H」。如需换个做法，直接告诉我。

**范围**：只改拒绝审批的回复；用户主动取消（`REPLY_CANCELLED`）、任务状态、节点状态均不变。

### 11.3 国际化

| 码 | 操作 |
|---|---|
| `reply.rejected_done` | g2rain-infra 新增 zh / en / ja 三条（`UI_MESSAGE` / `AGENT_RUNTIME`）；en 末尾带空格，便于与后半句拼接（zh、ja 以全角句号结尾，无需空格） |

### 11.4 改动清单

| 文件 | 改动 |
|---|---|
| `engine/flow/graph/DefaultGraphTransitionReducer.java` | `rejectedMessage` 取摘要首行、前置已完成步骤 |
| `i18n/RuntimeMessage.java` | 新增 `REPLY_REJECTED_DONE` |
| g2rain-infra `scripts/g2rain-infra.sql` | 新增 3 条 |

### 11.5 验证

1. 单元测试：无已完成写步骤 → 只有后半句；有 → 两段齐全；审批问题含明细行 → `{action}` 只取首行。
2. 重跑本场景：回复为「已完成：创建标签「测试G」。已取消，未执行：创建新标签「测试H」。……」。
