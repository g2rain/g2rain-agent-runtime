# 第二批 B3：续跑时技能装载失败（P1-5，现为 P2）

| 项 | 内容 |
|---|---|
| 状态 | 已实现 |
| 修复条目 | 扶正清单 P1-5（已降为 P2）；同时覆盖 agent-context 审计 P1-3（本体不可达时的表现与 A2 不一致） |
| 涉及类 | `AgentProfiles`、`DefaultHydrator`、`RuntimeErrorCode`、`RuntimeMessage` |
| 跨项目 | g2rain-server：`g2rain-infra.sql` 末尾追加 i18n 行，并同步到 bootstrap、deploy |
| 不涉及 | `DefaultBundleLoader`（契约非法时丢弃技能的行为不变）、SPI（`SpecSource` 的异常约定不变）、规划、图执行 |
| 关联文档 | [契约规范](../CONTRACT_SPEC.md) 第 16 节 |

---

## 1. 问题

1. **续跑时缺技能，节点在无契约的情况下运行**：`CONTINUE_TASK` 沿用已有的图，不经过规划。如果这一轮技能没装载上，`frame.findSkill` 返回 null，节点就在没有契约的情况下运行。技能没装载上可能有三种原因：agent-studio 暂时不可用、契约刚被改坏、本体不可达（`SpecSource` 抛异常后被丢弃）。
   - 节点没有完成目标时，`DefaultFinishPolicy` 在“无契约、无业务工具”的情况下直接放行完成，任务可能被判为成功，实际什么都没做。
   - 节点有完成目标时，只会因为没有进展而失败，但任务也就此失败了，用户要重新发起。
2. **依赖故障被当作“没有技能”**：`AgentProfiles.loadCapabilityBundle` 在远程调用失败时返回 null，和“确实没有技能”无法区分。开新任务时，用户看到的是“没有可用技能”这类提示，而不是“暂时不可用”。
3. **与 A2 不一致**：A2 约定本体不可达时**拒绝**，而不是让技能悄悄消失。

## 2. 设计

### 2.1 区分“调用失败”和“确实为空”

`AgentProfiles.loadCapabilityBundle`：

| 情况 | 行为 |
|---|---|
| 远程返回 null 或 `!isSuccess()` | 抛 `BusinessException(DEPENDENCY_UNAVAILABLE, "agent-studio")` |
| 成功但技能列表为空 | 返回 null（与现在相同，开新任务时规划失败） |

### 2.2 装载失败时本轮失败，任务保留

`DefaultHydrator.hydrate` 增加两道检查，任何一道不通过都**只让本轮失败，不动任务**：

1. **装载调用抛出 `DEPENDENCY_UNAVAILABLE`**：捕获后本轮失败，错误码 `DEPENDENCY_UNAVAILABLE`，文案 `RuntimeMessage.SKILLS_UNAVAILABLE`。
2. **续跑缺技能**：当前任务的图中有节点（说明是续跑，不是本轮新建的任务），图中任一节点引用的 `skillKey` 不在本轮装载结果里，本轮失败，错误码 `SKILL_UNAVAILABLE`（参数为缺失技能键，多个用逗号连接），文案 `RuntimeMessage.TASK_SKILLS_UNAVAILABLE`，并记 Trace（`TraceStage.HYDRATE`，事件名 `skills_missing`，带缺失的技能键）。

失败的方式与 `DefaultTaskLifecycle` 中“任务缺失”的分支相同：`ctx.setFailed(true)`，写入 `TurnResult`（状态 `FAILED`，带错误码和文案），**不调用** `turn.abort` / `turn.fail`。

- 流水线在 `hydrate` 之后会检查 `isFailed`，所以不会进入规划和图执行；
- `DefaultFinalizer` 遇到“已失败且已有结果”时只追加回复，不修改任务；
- 因此任务的状态、断点、图都保持原样，依赖恢复后用户再发一次消息就能继续（审批卡仍可确认）。

为什么检查图中**全部**节点，而不只是未完成的节点：已完成的上游节点的契约仍会被用到（`EvidenceReader` 按所属技能取抽取项，`GoalGate` 汇总证据）。缺了这些契约不会报错，但抽取会悄悄变空，结果不可靠。

### 2.3 不改的部分

- **契约非法的技能照样丢弃**（CONTRACT_SPEC 第 16 节），配置正确性由配置者负责。续跑时如果因此缺技能，按 2.2 第 2 条本轮失败，提示与依赖不可用相同。
- **`SpecSource` 的异常约定不变**（“无法提供时抛出异常，该技能按不可用处理”），本卡不改 SPI。开新任务时，如果某个技能因为本体不可达被丢弃，规划会用剩下的技能进行，这与现有行为相同。要在开新任务时也“本体不可达即拒绝”，需要 SPI 能区分“不可达”和“配置错误”，留到扶正 agent-context 时再定。

### 2.4 新增错误码与文案

| 类型 | 枚举 / 键 | 中文 | 英文 | 日文 |
|---|---|---|---|---|
| ERROR_CODE | `SKILL_UNAVAILABLE` / `agent_runtime.42259` | 技能不可用：{0:skillKey} | Skill unavailable: {0:skillKey} | スキルを利用できません：{0:skillKey} |
| UI_MESSAGE | `SKILLS_UNAVAILABLE` / `skills.unavailable` | 技能暂时无法加载，请稍后再试。 | Skills could not be loaded right now. Please try again later. | スキルを読み込めませんでした。しばらくしてから再度お試しください。 |
| UI_MESSAGE | `TASK_SKILLS_UNAVAILABLE` / `task.skills_unavailable` | 当前事项所需的技能暂时无法加载，事项已保留，请稍后再试。 | The skills this task needs could not be loaded right now. The task has been kept; please try again later. | この案件に必要なスキルを読み込めませんでした。案件は保持されています。しばらくしてから再度お試しください。 |

## 3. 代码改动

### 3.1 `AgentProfiles.loadCapabilityBundle`

按 2.1 修改。方法的 Javadoc 和行内注释（“远程失败视为未加载”）同步改写。

### 3.2 `DefaultHydrator.hydrate`

1. `bundleLoader.load(...)` 包在 try 中，只捕获错误码为 `DEPENDENCY_UNAVAILABLE` 的 `BusinessException`，其他异常照常抛出。捕获后按 2.2 第 1 条失败，然后 return。
2. 装载成功后（`setSkills` 和 `recordPins` 之后），调用私有方法 `missingSkills(task, skills)`，得到图中引用、但不在装载结果里的技能键（按图中节点顺序去重，跳过空白的 `skillKey`）。非空时按 2.2 第 2 条失败。
3. 失败结果的构造抽成私有方法 `failTurn(ctx, errorCode, message)`，两处共用。

### 3.3 `RuntimeErrorCode` / `RuntimeMessage`

按 2.4 新增一个错误码和两条文案。

### 3.4 Infra SQL

在 `g2rain-server/g2rain-infra/scripts/g2rain-infra.sql` **末尾**追加一条独立的 `INSERT`，接着现在最大的 id（6822）往后编号，共 9 行：
- 3 行 `ERROR_CODE`，`tag` 为 NULL；
- 6 行 `UI_MESSAGE`，`tag` 为 `AGENT_RUNTIME`。

不改已有行，然后把整个文件同步到 bootstrap 和 deploy 两处。

## 4. 文档同步

| 位置 | 修改 |
|---|---|
| CONTRACT_SPEC 第 16 节 | 补充：“续跑任务时，图中引用的技能有任何一个未装载成功，本轮失败（`SKILL_UNAVAILABLE`），任务保持原状；技能服务不可用时本轮失败（`DEPENDENCY_UNAVAILABLE`）” |

---

## 5. 任务卡（交给 Cursor）

**卡片 B3：续跑时技能装载失败**

**前提**：先读本文全文、CONTRACT_SPEC 第 16 节，以及 `docs/development/code-conventions.md` 的“编码细则”。只改本文开头表格中列出的类、Infra SQL 和文档。

**步骤**：

1. 按第 3 节改代码，追加 Infra SQL 并同步到另外两处。
2. 补齐下表的用例。
3. 按第 4 节同步文档。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| H1 | `AgentProfiles`：远程返回失败 | 抛 `BusinessException`，错误码 `DEPENDENCY_UNAVAILABLE` |
| H2 | `AgentProfiles`：成功但技能为空 | 返回 null |
| H3 | `DefaultHydrator`：任务图有节点 A、B，装载结果只有 A 的技能 | `ctx.isFailed()` 为真；结果状态 `FAILED`，错误码为 `SKILL_UNAVAILABLE` 的 code；**未调用** `turn.abort` / `turn.fail`；任务状态、断点不变 |
| H4 | `DefaultHydrator`：图中节点的技能全部装载成功 | 不失败 |
| H5 | `DefaultHydrator`：本轮新建任务（图为空），装载结果缺少部分技能 | 不失败（现有行为） |
| H6 | `DefaultHydrator`：`bundleLoader.load` 抛 `DEPENDENCY_UNAVAILABLE` | `ctx.isFailed()` 为真；错误码 `DEPENDENCY_UNAVAILABLE`；任务不变 |
| H7 | `DefaultHydrator`：`bundleLoader.load` 抛其他异常 | 异常照常抛出（不被吞掉） |
| H8 | `DefaultHydrator`：图中有节点的 `skillKey` 为空 | 跳过该节点，不算缺失 |

**验收**：H1～H8 全部通过；原有测试（`DefaultHydratorTest`、`DefaultBundleLoaderTest`）仍然通过；IDEA 无新增告警；Infra SQL 三处一致；文档已同步。

---

## 6. 补充：动作语义来源暂时不可用（agent-context X-2，2026-11-22 决定：方案 A）

### 6.1 问题

本卡（B3）只处理了续跑。新建任务时，如果某个技能的 `SpecSource`（例如本体插件）因为依赖不可达抛出异常，`DefaultBundleLoader` 会把它和“配置错误”一样处理：丢弃这个技能，规划继续用剩下的技能。这与本体决定 A2“本体不可达时拒绝”不一致。

- 数字员工只有本体技能时，规划会失败，提示是“没有可用技能”，与真实原因不符；
- 还有其他技能时，规划可能拿不相关的技能凑出计划。

### 6.2 设计

**在 SPI 中区分“暂时不可用”和“配置错误”。**

1. **Runtime api 新增 `spi/SpecUnavailableException`**，继承 `BusinessException`，表示动作语义来源暂时不可用（依赖不可达、超时等，稍后重试可以恢复）。构造方法有两个：
   - `(ErrorCode, Object... args)`
   - `(ErrorCode, Throwable cause, Object... args)`

   类 Javadoc 写明：`SpecSource` 实现在依赖暂时不可用时抛它；其他异常一律视为该技能配置错误。
2. **`SpecSource.load` 的 Javadoc** 改为：
   - 依赖暂时不可用时抛 `SpecUnavailableException`，本轮失败，任务保留，稍后可重试；
   - 其他异常表示该技能的来源配置有误，该技能按不可用处理（丢弃），不退回契约自带的 `tools`。
3. **`DefaultBundleLoader`**：
   - `loadSpecSet` 对 `SpecUnavailableException` **原样抛出**，不再归入“丢弃”；
   - 外层解析方法（`parseContract`）的 `catch (RuntimeException)` 之前，先加 `catch (SpecUnavailableException ex)`，记一条 error 日志（`reason=spec_source_unavailable`，带 skillKey），然后抛 `BusinessException(RuntimeErrorCode.DEPENDENCY_UNAVAILABLE, ex, type.name())`；
   - 这个异常会穿过 `toSkill`、`toSkills`、`load`，由 `DefaultHydrator` 已有的分支捕获（B3 第 2.2 节第 1 条），本轮失败，文案为 `SKILLS_UNAVAILABLE`，任务保持原样。
4. **agent-context**：`OntologyUnavailableException` 改为继承 `SpecUnavailableException`（其余不变）。它在拦截阶段同样会被 Runtime 按 `RuntimeException` 捕获，转为 `GUARD_UNAVAILABLE`，行为不变。

### 6.3 用例

| # | 场景 | 期望 |
|---|---|---|
| H9 | `DefaultBundleLoader`：某个技能的 `SpecSource` 抛 `SpecUnavailableException` | 抛 `BusinessException`，错误码 `DEPENDENCY_UNAVAILABLE`；其他技能也不再返回 |
| H10 | `DefaultBundleLoader`：某个技能的 `SpecSource` 抛普通 `RuntimeException` | 该技能被丢弃，其余技能正常返回（回归） |
| H11 | `DefaultHydrator`：新建任务（图为空），`bundleLoader.load` 因 H9 抛异常 | 本轮失败，错误码 `DEPENDENCY_UNAVAILABLE`（复用 H6 的断言方式） |
| H12 | agent-context：`OntologyUnavailableException` | 是 `SpecUnavailableException` 的子类 |

### 6.4 文档同步

| 位置 | 修改 |
|---|---|
| CONTRACT_SPEC 第 16 节 | 补充：“技能的动作语义来源暂时不可用（`SpecUnavailableException`）时，本轮失败（`DEPENDENCY_UNAVAILABLE`），新建和续跑任务都一样；来源配置错误时该技能被丢弃” |
| agent-context 审计第〇节 X-2 | 完成后改为“已修复” |
