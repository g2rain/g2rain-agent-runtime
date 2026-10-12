# 代码质量整改清单

来源：2026-10-11 全项目扫描（349 个 Java 文件，约 3.74 万行）。

状态说明：2026-10-11 已改完"本次直接改"一组（见第八节）与 R1、R2、R3；R4 待讨论，R5/R6 稍后单独做。代码均未编译验证。

优先级：**P0** 尽快改；**P1** 值得改；**P2** 顺手改。

约定：公共工具方法只在 runtime 内的 `RuntimeUtils` 定义，不新增到 g2rain-common（避免发布依赖）；g2rain-common 已有的方法照常优先使用。

---

## 一、过度补丁 / 过度设计

### R1 完成条件改为节点级 target【P0 · 已实施，待联调】

| 项 | 内容 |
|---|---|
| 现状 | doneWhen 是 finish 闸门的一环，但写在技能合约上（技能级、静态）。技能同时承担多种操作时合约无法知道本轮做哪个，只能把所有工具 OR 起来，查询一次就满足，闸门失效。契约已有 `target` 字段，但无人配置 |
| 原则 | "做完没有"取决于节点本轮目标：Planner 优先决定，契约给可选默认值，runtime 只校验事实。参考 customer-agent"规划优先、元数据兜底"，不搬它的表达式语言与多套默认值 |
| 契约 | 删除 `doneWhen`（及 `doneWhenExpr`、解析与求值代码）；保留可选 `target` 作为技能默认完成目标（单一写操作的技能配置，多用途技能不配）；ContractValidator 校验 target 必须是本技能的写工具 |
| 节点 target 取值 | Planner 输出 > 契约 `target` > 执行中首个实际发起的写调用（含被审批拦下或失败的）> 无（交 GoalGate） |
| 1. Planner 输入 | `PlanSkillItem` 带上该技能的写工具（由技能绑定工具 + 契约 `sideEffect: WRITE` 自动得出，作者不新增配置） |
| 2. PlanPrompt | 节点输出新增 `target`：要增删改就填对应写工具；纯查询不填。一个节点只允许一个 target，多步写由 Planner 拆成多个节点 |
| 3. Planner 校验 | target 必须是该技能的写工具；不合法则丢弃并 Trace warn，不让规划失败；Planner 未给时回落契约 target |
| 4. 单候选与保底 | 单候选 REACT 捷径保留（不额外调模型），节点 target 取契约 target；模型失败走候选串行保底时同样回落契约 target |
| 5. 节点模型 | `PlanStep`、`LlmPlanNode`、`TaskNode` 增加 `target`；`task_graph_node` 加 `target` 列，DTO/VO/PO/SelectDto/mapper/converter/SQL 同步 |
| 6. 事实兜底 | 节点无 target 时，首个实际发起的写调用自动记为该节点 target |
| 7. finish 闸门 | 基础校验不变（output 非空、无未收口写、无待补参、必填 output 齐全且来源合法）；有 target → target 工具（本节点或委派前置节点）有成功证据且业务未失败；无 target 且技能有业务工具 → 至少一次业务调用成功（空结果也算） |
| 8. 观测 | 节点无 target 且无写调用就结束时记 Trace |
| 9. ReAct 投影 | 节点信息展示目标工具（取节点 target）；PhaseHint 结合 target 与必填 output |
| 10. 删除 | `DoneWhenParser` / `DoneWhenExpr` / `DoneWhenKind`、`evaluateExpression` 及原子判断、仅被其使用的 ModelHint / 错误码、ContractValidator 的 doneWhen 校验、`ToolExecutionContext.getDoneWhen`、种子数据 doneWhen |
| 11. 文档 | CONTRACT_SPEC、STATE_SPEC、SKILL_GUIDE |
| 剩余口子 | 节点无 target 且从未发起写调用就结束。由 R3 修好后的 GoalGate 按用户目标判定未完成并如实回复 |
| 关联 | 拆节点后下游要用上游结果（如 siteId），上游契约须声明 output，R4 整理种子时一并检查 |

### R2 修复义务与 Observation「下一步」重叠【P0 · 已实施，待联调】

| 项 | 内容 |
|---|---|
| 现状 | 参数被拒时 `ParamContractInterceptor` 既返回带"下一步"的观测，又把同一文案写成 `RepairDirective` 挂在节点上（持久化），用于：每轮投影、拦后续调用、拦 finish、`repair-max-rounds` 熔断。另有 `RoundRepair` 在无工具调用 / finish 被拒时往历史插 user 消息 |
| 判断 | 义务携带的信息观测里都有；拦调用由每次调用重新过拦截器兜着；拦 finish 由 R1 target / 必填产出 / R3 GoalGate 兜着；熔断由无进展计数兜着 |
| 已删 | `RepairDirective` / `RepairDirectiveType` / `ArgRepairObligation`、`NodeScope` / `NodeSpace` 的 `repair` 及持久化、拦截器写义务、ReActLoop 义务拦截与清除、投影 `<repair>` 块（断点改为 `<checkpoint>` 块）、finish 第 3 步、`react.repair-max-rounds`、`RoundRepair` / `DefaultRoundRepair` / `RepairReason`、相关 ModelHint |
| 保留 | 无工具调用时提醒一句（`ModelHint.NEXT_USE_TOOLS`）并计无进展；finish 被拒只计无进展 |
| 行为变化 | 模型无视"下一步"时不再被硬拦，而是被同一拦截器再次拒绝并计无进展，超限节点失败 |
| 数据 | 旧节点空间（Redis / `task_graph_node.state`）含 `repair` 字段，联调前清理 |

### R3 GoalGate 只排版不核验，且拿不到证据【P0 · 已实施，待联调】

**现状（2026-10-11 读完调用链）**

| # | 问题 | 位置 |
|---|---|---|
| 1 | 证据包从未组装：`DefaultFinalizer.evaluate` 调用 `goalGate.present(executorMsg, null, succeeded)`，证据固定传 `null`。`GoalEvidenceAssembler.assemble`、`GoalEvidencePack`、`GoalNodeSummary`、`EvidenceView`、`adoptLongMessage`、配置 `nodeSummariesMax` / `dialogueTail` 全部空转 | DefaultFinalizer:121 |
| 2 | GoalGate 不判定目标是否达成，只决定"直接用草稿还是让模型润色"；任务是否成功完全取自图执行状态。R1 依赖它兜底"只读就结束"，目前接不住 | DefaultGoalGate.present |
| 3 | 多节点时回复只有最后一个节点的 finish 文案：`GraphFrame.completionMessage` 每次覆盖，前面节点的结果丢失；且只在本轮内存，跨轮（如审批挂起后续跑）前面轮次完成的节点结果拿不到 | GraphFrame:230、Reducer:192 |
| 4 | 是否调模型按字数判断（草稿 ≥ 24 字直接用，否则润色），与回复质量无关；短而准确的"已创建站点 A"会被送去润色，且润色时只有草稿本身 | DefaultGoalGate、`minDeterministicChars` |
| 5 | `GoalEvaluation.promoteFacts` / `writeContinuity` 无人读取 | domain/finalize/GoalEvaluation |
| 6 | `"连续无进展"` 硬编码中文（i18n） | DefaultReActLoop.stalledFail |

**方案草案**

| 项 | 内容 |
|---|---|
| 职责 | GoalGate = 目标核验 + 答复合成，只在任务成功收口时运行；失败、挂起、取消照旧直接用各自文案 |
| 节点结果 | `TaskNode` 增加 `result`（节点 finish 的 output），随图持久化（Redis + `task_graph_node.result` 列），替代 `GraphFrame.completionMessage` |
| 证据包 | 用户目标 + 每个节点（标题、objective、状态、target、target 是否完成、result）；去掉从未填充的 evidenceDigest / observationSnippets / joinSynthesis |
| 何时调模型 | 确定性规则代替字数：只有一个节点且（有 target 且已完成，或技能本身没有写工具）→ 直接采用该节点 result；否则（多节点，或节点无 target 但技能有写工具）→ 调模型 |
| 模型输出 | `{achieved, finalMessage}`：achieved=false 时 finalMessage 必须如实说明已完成与未完成部分，记 Trace `goal_unmet` |
| 删除 | `FinalMessagePicker`、`adoptLongMessage` / `fromSynthesis`、`minDeterministicChars`、`llmEnabled`（保留 `enabled` 总开关）、`GoalEvaluation.promoteFacts` / `writeContinuity` |

**已定**

- achieved=false 时任务状态保持 SUCCEEDED（节点都已执行完是事实），只把回复改为如实说明，并记 Trace `goal_unmet`
- 节点 result 落库：`task_graph_node.result VARCHAR(2000)`

**实施补充**：直接采用规则只看主节点（非委派前置）；模型关闭或失败时按图顺序拼接主节点结论；`GoalGate.present` 改为接收 `TurnContext`；顺带把 ReActLoop 中 5 处硬编码中文改为 `RuntimeMessage` / `ModelHint`，本次新增 Trace 事件名统一为 snake_case。

### R4 种子数据补丁规则【P1】

| 项 | 内容 |
|---|---|
| 位置 | KNOWN_ISSUES §3 已列：agent_profile 原则/风格行、manual"禁止 follow_up 口头确认"、枚举映射行、ID 来源强制 EVIDENCE |
| 方案 | R1、R2 落地后回头逐条评估删除（多为绕开 runtime 行为而加） |

---

## 二、低质量代码

| # | 位置 | 问题 | 方案 | 优先级 |
|---|---|---|---|---|
| R5 | `RuntimeStatePersistence`（1447 行） | 上帝类：conversation / message / task / graph / checkpoint / resume / call record / payload / entity 全混一起 | 按聚合拆为 ConversationStore、TaskStore、GraphStore、CallStore，外留薄 facade | P0（建议联调稳定后做） |
| R6 | `DefaultTurnScope` 730、`DefaultRuntimeStateFacade` 683、`DefaultPlanner` 669、`ParamContractInterceptor` 664、`DefaultNodeRunner` 627、`ReactProjectionFactory` 596、`EvidenceFlow` 540、`DefaultReActLoop` 517 | 职责偏多 | 随 R5 拆分；Planner 拆出候选筛选与 prompt 组装 | P1 |
| R7 | `EvidenceHead` | 18 字段位置参数 record，8 处全量构造拷贝 | 增加 `withXxx` 方法 | P0 |
| R8 | `FinalMessagePicker.Pick` source（`"EXECUTOR"`/`"FALLBACK"`，`DefaultGoalGate` 用 `"FALLBACK".equals`）；`StateSizeProbe` `"DEL"/"HDEL"/"EXPIRE".equals(op)`（`FlushOp.op` 为 String） | 固定值集合用字符串字面量 | 改为枚举 | P0 |
| R9 | `EvidenceFlow`（4 处 `Objects.isNull(result) ? null : …`） | 连续三元，可读性差 | 提前返回或抽方法 | P2 |
| R10 | `DefaultToolInvoker:162`、`EvidenceFlow:174`、`DefaultPlanner:342` | 捕获 Exception 后仅打日志继续 | 重抛，或转为明确的 Observation | P1 |
| R11 | ArgBinding、ArgPolicy、ParamContract、Fingerprint、EntryGuard、EffectHandler、GraphReducer、TaskGraphExecutor、FinalMessagePicker、PromptAssembler 共 19 处 `log.info` | 绕开 Trace 体系，部分打印大对象 | 统一走 Trace 或降为 debug，大对象截断 | P1 |
| R12 | 17 处 `Map<String,Object>`、11 处 `@SuppressWarnings` | 类型弱 | 能定义 record 的改 record | P2 |

---

## 三、重复代码

| # | 重复项 | 出现位置 | 方案 | 优先级 |
|---|---|---|---|---|
| R13 | `leafKey` | `RuntimeUtils`（公开）+ `SourceVerifier`、`ParamContractInterceptor`、`ContractValidator` 私有拷贝 | 统一用 `RuntimeUtils.leafKey` | P1 |
| R14 | `argsNode` | `EvidenceInterceptor`、`WriteGateInterceptor` | 提到拦截器公共工具 | P2 |
| R15 | 截断 | `TraceEvent.truncate`、`ResponseContractInterceptor.truncate`、`EvidenceFlow.clipChars`、`RuntimeStatePersistence.truncate`、`DefaultRuntimeStateFacade.act` 内联 | g2rain-common `Strings` 已有等价方法则直接用；否则在 `RuntimeUtils` 定义一个统一方法，各处改调它 | P1 |
| R16 | epoch ↔ LocalDateTime | `toInstant().toEpochMilli` 4 处、`ofInstant` 多处；`Moments` 无对应方法 | 在 `RuntimeUtils` 定义 `toEpochMilli` / `ofEpochMilli`，各处改调它 | P1 |
| R17 | 取当前时间 | `System.currentTimeMillis` 18 处 vs `Moments.now` 6 处 | 统一用 `Moments` | P2 |
| R18 | `JsonCodecFactory.instance()` | 42 个文件各持 static | 能注入的改注入；或集中到一处 | P2 |

---

## 四、死代码（确认无调用方/读取方，直接删除）

| # | 项 |
|---|---|
| R19 | `DoneWhenParser.containsUnknownAtom`（若 R1 走方案 B 则随整体删除） |
| R20 | `GoalEvidencePack.setEvidenceDigest` |
| R21 | `FlushOp.lrem` 及 `flush.lua` 的 LREM 分支 |
| R22 | `ToolErrors.isBusinessFailure` |
| R23 | `RuntimeStateFacade.putSecret` / `turnMessages`（接口 + 实现） |
| R24 | `NodeNoteKey.PROGRESS` / `NodeProgress`：每轮写 Redis，从不读取 |
| R25 | `PromptAssembleRequest.lastObservation`：传入未读 |

---

## 五、兼容遗留（开发期不保留兼容代码）

| # | 项 |
|---|---|
| R26 | `MessageType.CONFIRM_CARD`（"旧版…仅用于读取历史"） |
| R27 | `ArgPolicyInterceptor` 的 `$bag.path` "兼容残留"校验 |
| R28 | `ChatTurn` assistant 旧单 tool_call 字段：`toolCallId` / `arguments` / `toolName` |
| R29 | `RuntimeStatePersistence.loadNodeSpace` 三列旧格式回退 |
| R30 | `NodeSpace` 对 `actions` 为 null 的兼容处理 |

---

## 六、分组（已定）

| 分组 | 条目 |
|---|---|
| 本次直接改 | R7–R30（不含 R5、R6） |
| 先讨论 | R4（R1、R2、R3 已实施） |
| 稍后单独做 | R5、R6 |

## 联调修复（2026-10-11 第一轮 10 步）

| # | 问题 | 处理 |
|---|---|---|
| L1 | 首个写调用自动记为 target，可能只是前置写操作（第 7–8 步建分类成为写文章节点的 target），模型若就此 finish 会被 GoalGate 直接采用 | `TaskNode.targetClaimed`（库列 `target_claimed`）；自动记录的 target 不直接采用，交模型核验 |
| L2 | 拒绝审批后任务 FAILED、回复"处理没有成功，请稍后再试。" | 方案 B：不执行该写操作，节点 CANCELLED、任务 CANCELED，回复"已取消，未执行：{审批摘要}。如需换个做法，直接告诉我。"。状态用 CANCELED 而非 customer-agent 的 FAILED，理由是拒绝是用户决定而非执行出错；如需改为"拒绝后交还模型"（方案 A），只改 `closeByAction` 并在写闸加"同一调用被拒后不得再发起"的守护 |

| L3 | 第二轮第 1 步：意图模型空响应，直接"没有合适的数字员工" | F3 的空响应重试在统一提示词时被误删，已恢复；2026-10-12 重试已整体撤销，根因随 KNOWN_ISSUES Q8 处理 |
| L4 | 第二轮第 6–7 步：每轮落库失败 `target_claimed cannot be null`，前情与任务记录未落库（Q2 因此无法验证） | MapStruct 生成的转换器未重新生成（需 `mvn clean` 后构建）；插入路径另显式写入 `targetClaimed` |
| L5 | 意图模型输出 `"mode":"FRESH"`（把场景名当模式），解析失败后降级 | IntentPrompt 明确 mode 取箭头右侧的值、场景名不能作为 mode |
| L6 | "建文章分类"被选到 `cms.structure.manage`，模型去建了导航栏目；CMS 缺 `site_id` 返回系统内部错误 | 种子：11002 语义描述加入"文章分类"，11001 声明不负责文章分类；`post_channel_save` 的 siteId / spaceId 改为必填并配 `missing: CALL_TOOL`。CMS：栏目、页面、文章分类新增时校验必填归属 ID，返回参数缺失而非系统错误 |
| L7 | 保存工具只回主键（标量），实体抽取只认对象，新建对象不进前情；下一任务引用"刚建的空间"被判来源不明 | `entity` 新增 `scalar`：值为标量时按其命名成字段，可为任意属性；写工具以入参为底、返回为准合并后按字段名抽实体，抽取逻辑收敛到 `EntitySpec.seed`。种子 9 个 `*_saved` 抽取配 `"scalar":"id"`。CMS：6 个保存接口新增时为 `NOT NULL DEFAULT` 列补缺省值 |
| L8 | 节点 `userGoal` 用的是意图生成的 `goalSummary`，摘要丢了用户给的正文，模型反复追问正文 | `TaskMetadata` 新增 `request`（开任务时的用户原文，Redis 与 `task_instance.request` 落库）；节点 `userGoal` 与空历史种子改用 `request`，`goalSummary` 仍用于路由、前情与 GoalGate |
| L9 | 明确要求新建文章分类时，模型套用"写文章未指定分类先追问"的规则，多问一次 | 种子 11002 手册：目标补"文章分类的查询与创建"；新增"明确要求新建分类时直接调用写工具"；原分类规则限定为"写文章时" |
| L10 | 用户回答追问后，模型原样再问同一问题三次：回复在历史里是一条孤立消息，前面的追问结果仍是"已挂起" | 历史投影时，追问之后那一轮的用户回复改写为 `ModelHint.USER_ANSWERED`（问题、对应参数、回答）；`builtin_node_follow_up` 本轮刚回答的问题与参数原样再问时拒绝（`FOLLOW_UP_ANSWERED`），观察带回答并提示按回答继续 |
| L11 | 内置工具不分场景全量提供：无可委派技能仍给 `builtin_node_delegate`（误调被拒）；产出无人使用仍给 `builtin_output_set`，模型反复写选填产出被拒 3 次判停滞，任务失败 | `NodeToolset.of`（原 `DefaultNodeRunner.toolsFor`）只给本节点用得上的内置工具：有可委派技能且未到层级上限才给委派；有节点依赖本节点、或契约产出必填/共享/沉淀实体才给写产出。按内置工具动作（`BuiltinToolAction`）判断，见 R8 |
| L12 | 模型在调写工具前抢先申请审批，被 `APPROVAL_NOT_GATED` 拒绝后才去写，每次多一轮；用户说"已有的标签"但查询为空时模型直接新建 | `DefaultReActLoop` 每轮计算可见工具：没有被拦下待确认的写调用（`GATED_WRITE`）时不提供 `builtin_node_approval`，执行仍按全量查找；ReactPrompt 执行规则新增"用户指明使用已有对象而查询为空时，如实说明并追问是否新建，不要直接创建" |
| L13 | 审批工具按需提供后，模型在写之前看不到审批工具，改用 `builtin_node_follow_up` 口头确认，用户被确认两次 | ReactPrompt 写操作规则改为"需要确认时平台会拦下并提示，再按提示申请审批；不要用追问口头确认，也不要在调用写工具之前主动申请审批"，禁止口头确认由各技能手册提升为通用规则 |
| L14 | 追问没有准入依据（Q1）：口头确认、复述原话都能发起 | `builtin_node_follow_up` 新增依据校验：带待补参数时须为允许 `USER` 的工具参数，或须来自 `EVIDENCE` 且本节点已有非空查询；不带时须在最近一次业务调用结果为空或失败之后；否则拒绝 `FOLLOW_UP_UNGROUNDED`（42248）。没有契约的技能不判定 |
| L15 | 节点没有委派工具时，ReactPrompt 仍固定写"需要其他技能先完成时调用 builtin_node_delegate" | 删掉 ReactPrompt 中的固定委派说明；委派说明随 `delegateCatalog` 投影（`ModelHint.DELEGATE_CATALOG`），只在本轮提供了委派工具时出现 |
| L16 | 首次写调用被认领为完成目标后，阶段提示说"完成目标已成功调用"，可能诱导多写技能提前收尾（如建完标签就结束、漏掉绑定） | 认领的目标视为推测：`node_goal.target` 只投影声明的目标（规划或契约给出）；`PhaseHint` 对认领目标成功后改为"已完成写操作 X；用户诉求如还有未完成的写操作就继续，否则调用 builtin_node_finish"（`PHASE_WRITE_DONE`）。FinishPolicy 与 GoalGate 不变 |
| L17 | 多节点任务中上游查询结果只能靠模型 `builtin_output_set` 手抄给下游，值须与证据完全一致，模型反复写被拒（`SOURCE_REJECT`） | 参照 customer-agent 的依赖证据投影，但只用一条可见性规则：节点可读沿依赖边追溯（含委派）的全部上游节点证据（`TaskGraph.upstream`）。`EvidenceReader` 读取范围、`EVIDENCE` 来源校验同步扩大；新增 `<upstream_evidence>` 投影上游抽取原文（超长占位，可按 evidenceId 读取）；`builtin_output_set` 不再因"有下游依赖"而提供；下游未结束时保护上游报文热态。state 结构不变 |
| L18 | 问"页面"被分给栏目技能，答非所问：11003 描述要求"仅当明确要求静态页"，11001 只排除"静态页面正文" | 种子：11003 改为"负责 CMS 页面（静态页）。本轮要求查询、创建、发布、撤回或删除页面时选用"；11001 改为不负责"页面（静态页）" |
| L19 | 技能筛选输出先写 `candidateSkillKeys` 后写 `reason`：模型在 reason 里推出"栏目和页面需要两个技能"，结论却已只写了一个；也漏选前置技能 | 决策类提示词统一"先 reason 后结论"：SkillCandidate、Intent 把 `reason` 提到首位；Plan、GoalGate 新增首位 `reason`（解析忽略未知字段，代码不变），并要求结论与 reason 一致 |
| L20 | 新会话问"《五分钟上手》打了哪些标签"：规划据手册以为标签技能能定位文章，只出一个节点，标签技能查不到文章（Q9） | 种子：11004 绑定并声明 `get_article_list`；`articleId` / `articleIds[]` 来源去掉 `USER`，改为 `EVIDENCE` / `PRIOR_TASK`，必填处 `missing: CALL_TOOL get_article_list`；手册改为"前情没有时按标题查询定位，不向用户要文章 ID" |
| L21 | 两节点计划（定位空间 → 建分类）：节点 1 照用户原话把分类也建了，节点 2 再建同一分类时复用了节点 1 的成功结果，但完成目标只认本节点证据，finish 连续被拒判停滞 | 复用其他节点的成功写结果时，`DefaultRuntimeStateFacade.adopt` 以原报文在本节点补记一条成功证据（`RuntimeStateFacade.existing` / `prewrite` 共用） |
| L22 | 同一写操作审批两次：写闸门先要确认、后查重，已写成功的操作也会再次要求确认 | `WriteGateInterceptor` 先经 `existing` 查同一写操作：已成功直接复用、进行中或待核实则等待，都不再申请确认；没有才走确认与预写（`EvidenceFlow.existing` 由 `prewrite` 共用） |
| L23 | `builtin_state_set` / `builtin_output_set` 的 `Object value` 被 Spring AI 当作上下文参数注入成 `ToolContext`，便签与产出存的是整个执行现场 | `value` 改为 `JsonNode`，写入前经 `RuntimeUtils.plain` 转普通值；产出值为空时拒绝 |
| L24 | 多节点时节点被用户原话带着越界执行 | ReactPrompt：`userGoal` 只用来理解背景和取用细节，本节点只做 objective 范围内的事 |

观察项（暂不处理）：一次业务动作含多次写时审批次数多（打标签 3 次）；模型会采纳自己给出的建议发起写（第 7 步建分类），由审批兜底；多用途技能的查询会多一次 GoalGate 模型调用；Q1 是否已缓解需撤掉补丁再测一轮。GoalGate 也出现空回复与提前截断（同 Q8）。

## 七、待确认

- [ ] R4 种子补丁规则：Q1 相关补丁（禁止口头确认、枚举直接映射）需撤掉补丁跑一轮再定；Q2 的 ID 来源补丁已随 Q2 修复处理（见 KNOWN_ISSUES Q2）
- [ ] Q0 任务生命周期：Q2 修复后观察跨任务衔接是否足够，再决定是否改
- [x] R5（2026-10-12）：`RuntimeStatePersistence` 按表拆为 `engine.state.repo` 下 `ConversationRepository`、`TaskRepository`、`GraphRepository`、`CallRepository`、`PayloadRepository`（共用 `RepoSupport`），原类只留轮末编排 `persistTurn`；调用方直接注入所需仓储；新建任务行后由编排回填触发消息的 task_id（原在任务写入内部完成）
- [x] R6 第一批（2026-10-12）：`DefaultRuntimeStateFacade` 拆出节点历史重建 `NodeHistory`（710 → 495 行）；`DefaultTurnScope` 拆出交互历史与刷写游标 `DialogueLog`（730 → 701 行，其余为接口访问器，不再拆）
- [x] R6 第二批（2026-10-12）：`DefaultPlanner` 拆出技能候选筛选 `SkillSelector`、模型出图 `PlanDrafter`，`SkillItem` 提为包内 record（740 → 371 行）；`ReactProjectionFactory` 拆出工具目录投影 `ToolCatalogProjector`（596 → 518 行，其余为每块一个方法，不再拆）
- [x] R6 第三批（2026-10-12）：`ParamContractInterceptor` 拆出 `SourceResolver`（入参可信来源判定，622 → 504 行）；`DefaultNodeRunner` 拆出 `NodeToolset`（节点工具集）与 `WriteVerifier`（未知写收口，672 → 441 行）。`EvidenceFlow`、`DefaultReActLoop` 职责单一，不拆
- [x] R7 清理（2026-10-12）：删除无调用方法 `ToolErrors.readStatus/writeStatus`、`DefaultTurnScope.dialogueMax`；去掉从未实例化的 `ResponseContract`，成功条件并入 `SuccessRule.Condition`，错误路径改为 `ResponseContractInterceptor` 私有 record（`ModelGateway.embed` 预留向量化，保留）。包收拢：`react.projection` 并入 `react`；`engine.hydrate.ContractValidator` 并入 `engine.flow.hydrate`；`adapter.AgentProfiles` 并入 `client`；`Platform`、`ConversationFence` 并入 `domain.state`；`PlatformCatalog` 并入 `support`；`GoalEvaluation` 并入 `domain.decision`
- [x] R8 内置工具按动作识别（2026-10-12）：去掉 `BuiltinTools` 里的工具名常量（接口只作内置工具 Bean 的类型边界），业务代码不再比较工具名。`ToolCallbacks` 新增 `is` / `offers` / `without`，按 `BuiltinToolCallback.action()` 判断；`BuiltinToolAction` 新增 `OUTPUT` 区分写产出与写便签。`NodeToolset`、`DefaultReActLoop` 按动作筛选；`RuntimeStateFacade.history` 增加本节点可见工具参数，`NodeHistory` 用它按 ASK 动作识别追问调用。内置工具判断统一走 `ToolCallbacks`：新增按类别判断的 `is(callback, category)`，`FingerprintInterceptor` 的控流/节点类豁免收为 `exempt`，`ToolCatalogProjector` 改用 `isBuiltin`

## 八、本次执行结果

| # | 结果 | 说明 |
|---|---|---|
| R7 | 已改 | `EvidenceHead` 增加 `withStatus` / `withOutcome` / `withResult` / `withPayloadId`；全量拷贝构造只剩 3 处真正新建（写索引命中、STARTED 新建、库表回灌） |
| R8 | 已改 | `FinalMessagePicker.Source` 枚举；`FlushOp.Op` 枚举（带 `writes()`），`StateSizeProbe` 按 `writes()` 统计 |
| R9 | 已改 | `EvidenceFlow.headOf` 拆为 `startedOf` + `withResult`，连续三元消除 |
| R10 | 已改 | `DefaultPlanner` 三处吞异常加 Trace warn，`catch (Exception)` 收窄为 `RuntimeException`；`EvidenceFlow.unknownPreCall` 补 Trace warn。`DefaultToolInvoker` 两处（入参摘要、非 JSON 返回按文本）属于正常兜底，保留 |
| R11 | 已改 | 拦截器、Reducer、EffectHandler、PromptAssembler 的过程日志降为 debug；指纹预警、审批过期、任务缺失改走 Trace |
| R12 | 部分 | `syncTaskStatus` 只有一种调用，收为 `syncSuspended`；`insertMessage` 返回值无人用，改为 void。其余 `Map<String,Object>` 都是通用 JSON / 便签值，属合理用法；其余 `@SuppressWarnings` 为 IDE 检查抑制，保留 |
| R13 | 已改 | `RuntimeUtils.leafKey` 统一为剥数组下标的实现，删除 3 份私有拷贝（原公开版本无人调用） |
| R14 | 已改 | `RuntimeUtils.argsNode` |
| R15 | 已改 | `RuntimeUtils.clip`（硬截断）/ `abbreviate`（截断加 `…`）；替换 Trace、ResponseContract、EvidenceFlow、Facade.act、Persistence、IntentClassifier、GoalGate、ReactProjection 共 8 处。ResponseContract 的后缀由 `...` 统一为 `…` |
| R16 | 已改 | `RuntimeUtils.epochOf` / `timeOf`，替换 Persistence 内全部转换 |
| R17 | 不改 | 复核后不是重复：`System.currentTimeMillis` 用于热态毫秒时间戳，`Moments.now` 用于库表 `LocalDateTime`，类型不同、各自合理 |
| R18 | 不改 | `JsonCodecFactory.instance()` 是 g2rain-common 提供的单例入口，静态常量持有是惯用写法；改注入只增加样板代码 |
| R19–R25 | 已删 | 另：`R23` 连带删除 `SessionKeys.secret`、`RedisKeys.SECRET`；`R25` 连带删除 ReActLoop 内的 `lastObservation` 局部变量 |
| R26 | 已删 | 枚举、DTO/VO/PO 注释、两份 SQL 注释、STATE_SPEC / SSE_EVENTS 文档同步 |
| R27 | 已删 | 连带删除 `RuntimeErrorCode.ARG_UNRESOLVED_REF`（40021）、`ModelHint.ARG_UNRESOLVED_REF` / `NEXT_RESOLVE_REF`，CONTRACT_SPEC / TOOL_OBSERVATION 同步 |
| R28 | 已改 | `ChatTurn` 去掉 `arguments`，`toolCallId` / `toolName` 只用于 TOOL 轮，新增 `ChatTurn.tool(...)`；`DefaultNodeRunner` 删除旧单 tool_call 分支 |
| R29 | 已改 | `loadNodeSpace` 只读 `state` 列完整 NodeSpace |
| R30 | 已改 | `NodeSpace.actions` 与其他字段一致直接 `List.copyOf` |

**开发数据需清理**（因去掉兼容）：库里 `message_type = CONFIRM_CARD` 的 `chat_message` 行、`task_graph_node.state` 为旧格式（不是完整 NodeSpace 或缺 `actions`）的行，以及 Redis 里旧格式的节点空间，读到会反序列化失败。

**R3 新发现**：`GoalEvidencePack` 的 `evidenceDigest`、`observationSnippets`、`joinSynthesis` 从未被填充；`GoalEvidenceAssembler` 生成的 `nodeSummaries` 里 `resultSummary`、`finalMessage` 恒为 null。因此 `adoptLongMessage` 实际总返回 null，GoalGate 拿到的证据基本为空，GoalGatePrompt 里对这几个字段的说明也是空转。本次只删了无人调用的 `setEvidenceDigest`（R20），字段与提示词留到 R3 一并处理。
