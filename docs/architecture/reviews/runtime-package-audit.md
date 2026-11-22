# g2rain-agent-runtime 包审计：工具调用、状态持久化、规划与委派、并发与恢复

| 项 | 内容 |
|---|---|
| 版本 | v5（2026-11-22）：定稿——合并两轮校准与清单 11，形成唯一扶正清单（第〇节）；后文保留为审计原文 |
| 依据 | STATE_SPEC.md、CONTRACT_SPEC.md、SKILL_GUIDE.md、TOOL_OBSERVATION.md、KNOWN_ISSUES.md、design/state-audit-fixes.md；各包 package-info 与类、方法 Javadoc |
| 方法 | 第一遍：去注释逐类读完全部代码，追踪状态链路；第二遍：对每条发现读完整源码（含 Javadoc 与行内注释）、补读相关调用方，并对照文档判定它是缺陷、文档已声明的取舍，还是文档缺口 |
| 结论 | P1 8 项（P1-1～P1-4 为状态与工具链路，P1-5～P1-8 为本次补审新增）；P2 22 项；设计建议 6 项。商用前须关闭全部 P1 |

## 〇、扶正清单（定稿）

本节是**唯一的扶正依据**。它合并了四部分内容：本审计，2026-11-22 两轮校准，《runtime-weaknesses.md》第三、四节（状态层复杂度、核心类缺测试），以及 `g2rain/ontology/g2rain-ontology-docs/11-runtime-hardening.md`（下称“清单 11”，R/D/G 编号；其中的结论为 2026/11/16 所定）。后文第三节以下保留审计原文以备查阅，**定级与处理一律以本节为准**。

处理方式：**直接修**＝不需要拍板；**需拍板**＝涉及设计取舍，等你决定；**撤回**＝与既有结论冲突或经核实不成立；**后置**＝条件不具备，留待以后。

### 0.1 P1（校准后）

| # | 问题 | 校准后定级 | 处理 | 批次 | 说明 |
|---|---|---|---|---|---|
| P1-1 | `evidence_get`、`SourceVerifier` 读证据不限于本节点与上游 | **硬伤** | **已修复**（2026-11-22，B1） | 第二批 | 需要知道 UUID 才能利用，按 P1 处理 |
| P1-4 | 中断规整只改 Redis 中的 `STARTED`、且只处理 RUNNING 任务；三条冷恢复路径会卡在 `WRITE_IN_FLIGHT` | **硬伤** | **已修复**（2026-11-22，S1-1） | 状态层第一步 | 与清单 11 §6“中断后的规整”同一处，现有实现覆盖不全 |
| P1-4b | 写调用后、轮末 persist 前崩溃时，MySQL 中有 `STARTED` 写证据，却没有对应的任务行或图节点（新任务首轮、已有任务中新增的节点），冷恢复无法规整 | P1-4 的剩余边界 | **已修复**：S2 预登记前补写任务壳（只插入库中没有的任务行、节点、边，并修正会话活动任务），见 `docs/design/state-task-shell.md` | 状态层第一步之后（独立卡） | 2026-11-22 你确认。只在新任务首轮第一次调用写工具时多写一次库；触发需同时满足首轮直接写、本轮崩溃、超过 24h 才回来或 Redis 丢数据 |
| P1-6 | 模型调用读超时为 0，加上看门狗续期，会话可能永久锁死 | **硬伤**（2026-11-22 你的判定） | **不改**（2026-11-22 你决定：超时由 model-nexus 负责；大模型处理时间差异大且通常很慢，runtime 侧设任何读超时都不合适。维持清单 11 R-26） | 第二批 | 与清单 11 R-26“不改”（理由：model-nexus 调上游有 60 秒超时）冲突。风险只在 model-nexus 本身或连接卡住时出现。请决定：设兜底读超时（参照 R-19 的 MCP 做法），还是维持 R-26 |
| P1-2 | 完整报文与状态同批 flush，单个报文超限时工具已执行、本轮失败 | 机制成立，场景窄 | **已修复**（2026-11-22，方案 B：超过内联上限的报文只进 MySQL；flush 上限只校验状态，S2-1） | 状态层第二步 | 正常情况下每次结算都会清空本轮报文，不会叠加 |
| P1-3 | persist 两次失败后，已消费的断点从 MySQL 复活 | 机制成立，场景窄 | **已修复**（2026-11-22，S1-1） | 状态层第一步 | 不会自动弹出审批卡；用户显式再点 CONFIRM 才会按旧的待执行操作续跑。**注意**：哨兵定组会改变清单 11 §6 认为扎实的“逐项回灌”，属于设计变更，在状态层第一步的设计里请你确认 |
| P1-5 | 续跑时技能装载失败，节点在无契约状态下运行 | **降为 P2** | **已修复**（2026-11-22，B3：续跑缺技能或技能服务不可用时本轮失败、任务保留） | 第二批 | 节点有完成目标时仍要求目标写成功，只会因无进展失败；只有无目标节点可能被误判成功。同时修复 agent-context 审计 P1-3（本体不可达时的表现） |
| P1-7 | 提示词超长时从尾部整块丢弃且不记录 | **降为 P2** | 丢块记 Trace **已修复**（B6）；裁剪策略与 history 预算定为**观察项**（2026-11-22 你同意）：联调时看 `prompt_blocks_dropped` 与节点 `tokens`，有数据再决定 | 第二批 | 清单 11 R-49 已保证 `node_goal` 不被丢；history 受轮次与观测长度约束，有界；`tool_catalog` 丢了，工具定义仍通过 `tools[]` 传给模型 |
| P1-8 | 生成的 CRUD 接口提供写操作与全量查询 | **撤回** | — | — | 清单 11 D-22 已定：生成接口不注册为对外资源。保留为部署核对项 |

### 0.2 跨项目

| # | 问题 | 处理 | 批次 |
|---|---|---|---|
| X-1 | `EvidenceReader.nodeCalls` 用 `argsBrief`（脱敏、截断）作为前序调用参数传给 `ToolGuard`，本体据此判断是否已补查 | **已修复**（2026-11-22，B1：Guard 前序调用参数改为 `tool_call_record.request` 完整入参） | 第二批 |

本体审计 P1-4 与 agent-context 审计 P1-2 都依赖这一项。触发条件比原先写的窄：要求靠“查过”来匹配，并且主键恰好在摘要中丢失。

### 0.3 P2

| # | 问题 | 处理 | 批次 |
|---|---|---|---|
| P2-1 | 换任务时旧断点不消费（恢复审计按新任务 ID 写） | **已修复**（2026-11-22，B2） | 第二批 |
| P2-2 | 删除任务组与清空 `activeTaskId` 分两次 flush | **已修复**（2026-11-22，S3-1） | 状态层第三步 |
| P2-3 | 节点 `errorCode` 写的是状态名 | **已修复**（2026-11-22，B2） | 第二批 |
| P2-4 | 超大报文的 `payloadId` 未落 MySQL | **已修复**（随 P1-2，S2-1） | 第二批 |
| P2-5 | `TaskRecord` 生成两次，`closedAt` 不一致 | **已修复**（2026-11-22，S3-1） | 第二批 |
| P2-6 | persist 重试时实体 `mentions` 重复累加 | **已修复**（2026-11-22，B5） | 第二批 |
| P2-7 | `NodeFinisher` 先置 DONE 再写共享值，后者可能抛异常 | **已修复**（2026-11-22，B2） | 第二批 |
| P2-8 | 节点便签 `Map<String,Object>` 往返后丢失类型 | **后置**：与清单 11 R-56② 同源，待 g2rain-common 提供 `JsonCodec.convert` | — |
| P2-9 | 派发前的 `argsHash` 比对是同一对象自比，恒相等 | **已修复：删除检查**（2026-11-22，见 `docs/design/toolchain-fixes.md` 第 8 节；清单 11 R-52 已更正） | 第二批 |
| P2-10 | `builtin_node_skip` 不检查 `LOOKUP_PENDING`，契约规范里也没有这个工具 | **已修复**（2026-11-22，B2） | 第二批 |
| P2-11 | `TaskGraph.resumeAwaiting` 硬编码错误码，并绕过 `mark` | **已修复**（2026-11-22，B2） | 第二批 |
| P2-12 | 拒绝详情中的中文字面量拼接 | **撤回**：清单 11 R-55 结论（给模型的说明只有中文） | — |
| P2-13 | `from` 前缀散落解析；回落幂等键重复实现 | **已修复**（2026-11-22，B4） | 第二批 |
| P2-14 | ① 一次 CONFIRM 结算全部不确定写操作；② `getLast()` 被 `applyOutcome` 打乱顺序 | ① 撤回（清单 11 D-9 不改）；② **已修复**（2026-11-22，B4：`addEvidence` 原位替换） | 第二批 |
| P2-15 | 杂项 | `EnvBuiltinTools`、回查调 `listTools` 撤回；其余**已修复**（2026-11-22，B4） | 第二批 |
| P2-16 | `advanceStateVersion` 不更新 `skill_versions` | **已修复**（2026-11-22，B5） | 第二批 |
| P2-17 | `touch` 全行回写 | **已修复**（2026-11-22，B5） | 第二批 |
| P2-18 | 客户端未按 `toolNames` 过滤注册中心返回的工具 | **已修复**（2026-11-22，B4） | 第二批 |
| P2-19 | `platform` 原值投影给模型 | **撤回**：清单 11 D-21 已改为白名单，平台字段按设计进入提示词 | — |
| P2-20 | SSE 的 `streamId` 与回调线程中的 traceId 可能不一致 | **已修复**（2026-11-22，B4） | 第二批 |
| P2-21 | 三个配置项无人读取 | **已修复**（2026-11-22，B4：删除三项配置） | 第二批 |
| P2-22 | 模型调用 5xx 整包重发、无退避 | **撤回**：清单 11 R-50 不改 | — |

### 0.4 提示词

| # | 问题 | 处理 |
|---|---|---|
| PR-1 | 规划拿到的 `continuitySummary` 是字面量 `"start"` | **已修复**（2026-11-22，B6）
| PR-2 | 收口核验时节点结论截到 500 字，最终答复可能缺内容 | **已修复**（2026-11-22，B6）
| PR-3 | 核验证据混入业务失败的调用 | **已修复**（2026-11-22，B6）
| PR-4 | 失败原因中夹带上游原文，可能伪造“下一步：” | **已修复：方案 A**（2026-11-22：上游原文包进「」并标明来源，规则 3 扩展到失败场景，见 `docs/design/prompt-fixes.md` 第 12 节）
| PR-5 | `Accept-Language` 原文作语言标签 | **撤回**：网关已规范为单个标签 |
| PR-6 | 审批挂起时用户用文字要求修改，被统一改判为 `UNCERTAIN` | **方案 C**（2026-11-22 你决定：维持现状，不新增修改通道；只改提示词，修改诉求按 REPLY_ONLY 回复“请先拒绝再重新描述”，见 `docs/design/prompt-fixes.md` 第 10 节）
| PR-7 | `<checkpoint>` 投影块在 ReAct 期间永远为空 | **已修复：删除**（2026-11-22，见 `docs/design/prompt-fixes.md` 第 11 节）
| PR-8 | 技能筛选没有说明“无合适技能”时如何输出 | **已修复**（2026-11-22，B6）
| PR-9 | `IntentRequest.recentEpisodes` 是死字段 | **已修复**（2026-11-22，B6）
| PR-10 | 同 P2-19 | **撤回** |

### 0.5 设计建议

| # | 处理 |
|---|---|
| D-1（ID 参数配 `entity`） | **撤回**：清单 11 D-2 已提供 `entity`，CMS 种子已补 |
| D-2、D-5（装载期校验、未配置工具默认只读） | **撤回**：清单 11 R-3、R-9 不改 |
| D-3（子节点 DONE 后可重复委派） | **不改**（2026-11-22 你决定：只读、无副作用，代价只是多一次查询；拦截反而会挡住合理的重新查询） |
| D-4（历史中的调用参数是摘要） | **已修复：方案 A**（2026-11-22：跨轮历史改用脱敏后的完整参数，见 `docs/design/history-args.md`） |
| D-7（`TurnDecision.useContinuity` 从未被读取，`START_FRESH` 的节点也装入前情） | **已处理：方案 B**（2026-11-22：节点层维持装入前情作为兜底，`useContinuity` 只影响规划层；只改注释，见 `docs/design/prompt-fixes.md` 第 13 节） |
| D-6（限流与配额） | **后置**：放在网关层 |

### 0.6 批次

状态层三步对应《runtime-weaknesses.md》第三节“状态层复杂度”的扶正思路，P1-2、P1-3、P1-4、P2-2 都源自状态层复杂度，随这三步一起修。

| 批次 | 内容 | 前提 |
|---|---|---|
| 状态层第一步 | P1-4、P1-3：哨兵定组 + 空值显式化 + 完整性校验 + 冷恢复规整，见 `docs/design/state-group-recovery.md` | **已完成**（2026-11-22，编译测试通过） |
| 任务壳预落库 | P1-4b：S2 前补写任务壳 | **已完成**（2026-11-22，S1-2） |
| 状态层第二步 | P1-2 已修复（S2-1）；按变更写入 + 一个结构一个编解码 **已完成**（2026-11-22，R1、R2，见 `docs/design/state-refactor.md`） | — |
| 状态层第三步 | P2-2：收尾原子化（顺带 P2-5） | **已完成**（2026-11-22，S3-1） |
| 第二批 | 其余“直接修”的条目，按类分组拆成任务卡，可与状态层并行，但不碰状态层的类 | — |
| 拍板后 | 全部已决定（2026-11-22） | — |

每一步完成后，同步改写 STATE_SPEC 中受影响的章节（第 2、4、5、8 节）。

### 0.7 测试要求（随批次补齐）

对应《runtime-weaknesses.md》第四节“核心类没有单元测试”。测试不单独开批次，而是作为每张任务卡的验收条件：

1. **先补测试再改**：任务卡涉及的类如果还没有测试，先写测试固定现有的正确行为，再动代码。
2. **缺陷要有用例**：每个被修复的条目至少有一个用例，修复前失败、修复后通过。
3. **状态层要做故障注入**：至少覆盖以下场景：
   - 哨兵缺失（Redis 丢失或过期）后冷恢复；
   - 中断后续跑（`lastTraceId` 非空），任务处于 RUNNING 或非 RUNNING；
   - 写操作进行中崩溃（`STARTED` 证据）；
   - persist 失败与重试；
   - flush 超限；
   - 收尾中途失败。

各批次需要覆盖的类：

| 批次 | 需覆盖的类 |
|---|---|
| 状态层第一至三步 | `TurnSessionHydrate`、`TurnFlushPlan`、`RuntimeStatePersistence`、`DefaultRuntimeStateFacade`、`EvidenceFlow`、`DefaultTurnEntryGuard` |
| 第二批 | 改到哪个类就覆盖哪个，重点是 `WriteGateInterceptor`、`ParamContractInterceptor`、`DefaultNodeRunner`、`WriteVerifier`、`DefaultGraphTransitionReducer`、`TaskGraph`、`PlanGraphs`、`DefaultTaskLifecycle` |

## 一、范围与覆盖

### 1.1 已审计（逐行读完）

| 包 | 类（行数） | 合计 | 全文读完 |
|---|---|---|---|
| `engine.tools` | DefaultToolInvoker(542)、ToolErrors(218)、ToolEvents(204)、ToolExecutionContexts(57)、ToolInterceptor(41)、ToolInvoker(27)、ToolRejectedException(55) | 1144 | 是 |
| `engine.tools.builtin` | ControlBuiltinTools(410)、EnvBuiltinTools(207)、HaltResolver(164)、NodeBuiltinTools(240)、SessionBuiltinTools(59) | 1080 | 是 |
| `engine.tools.guard` | ArgBindingInterceptor(480)、ArgPolicyInterceptor(189)、EvidenceInterceptor(142)、EvidenceReader(557)、EvidenceSemantics(230)、FingerprintInterceptor(124)、GuardInterceptor(194)、IdempotencyKeyBuilder(99)、ParamContractInterceptor(504)、ResponseContractInterceptor(203)、SourceResolver(160)、SourceVerifier(354)、SuccessRule(244)、WorkingTree(148)、WriteGateInterceptor(215)、WriteScopeInterceptor(80) | 3923 | 是 |
| `engine.tools.finish` | DefaultFinishPolicy(238)、FinishContext(57)、FinishDecision(30)、FinishPolicy(21)、NodeFinisher(61) | 407 | 是 |
| `domain.tool` | Observation(178)、Outcome(66)、ToolCall(40)、ToolExecutionContext(485)、ToolInvokeRequest(311)、ToolResult(55) | 1135 | 是 |
| `support.tool` | BuiltinToolAction(106)、BuiltinToolCallback(84)、BuiltinToolCallbackProvider(92)、BuiltinToolCategory(41)、BuiltinToolKind(37)、BuiltinTools(15) | 375 | 是 |
| `support.mcp` | McpToolCallback(140)、McpToolCallbackProvider(169) | 309 | 是 |
| `engine.store` | FlushOp(143)、KeyTtl(14)、RedisStateStore(189)、RenewResult(25)、StateStore(91) | 462 | 是 |
| `engine.state` | DefaultNodeScope(270)、DefaultRuntimeStateFacade(545)、DefaultTurnScope(854)、DialogueLog(115)、EntityExtractor(296)、EntitySeeds(84)、EvidenceFlow(501)、HashCodec(185)、InputBinder(191)、NodeHistory(282)、OutputContractValidator(168)、PrewriteResult(41)、RuntimeStateFacade(169)、RuntimeStatePersistence(164)、SessionKeys(140)、StateSizeProbe(92)、TaskRecords(60)、TurnFlushPlan(403)、TurnSessionHydrate(551)、WriteIndexEntry(56) | 5167 | 是 |
| `engine.state.repo` | BlockRepository(51)、CallRepository(301)、ConversationRepository(243)、GraphRepository(278)、PayloadRepository(120)、ProgressRepository(72)、RepoSupport(74)、TaskRepository(435) | 1574 | 是 |
| `domain.state` | ConversationFence(27)、NodeNoteKey(69)、NodeScope(108)、Platform(81)、Source(20)、Sourced(21)、TurnScope(246)、TurnStatus(62) | 634 | 是 |
| `domain.state.dialogue` | DialogueMessage(92)、DialogueRole(25)、MessageType(35) | 152 | 是 |
| `domain.state.progress` | ProgressStep(92)、StepStage(42)、StepStatus(27)、StepView(24)、TurnProgress(147) | 332 | 是 |
| `domain.state.task` | CheckpointType(74)、ClosedTask(34)、DelegateResult(21)、EntityEntry(34)、EntitySeed(18)、EvidenceHead(115)、EvidenceStatus(67)、FailureKind(61)、HaltReason(59)、NodeAction(63)、NodeSpace(33)、PayloadType(24)、PendingAction(63)、Prior(29)、RejectedAction(15)、ResumeAction(67)、SpaceSpill(41)、TaskCheckpoint(71)、TaskMetadata(112)、TaskRecord(41)、TaskStatus(78) | 1120 | 是 |
| `engine.flow.plan` | DefaultPlanner(411)、PlanDrafter(297)、PlanGraphs(158)、Planner(23)、ProviderNodes(290)、SkillItem(26)、SkillSelector(167) | 1372 | 是 |
| `domain.plan` | LlmPlanNode(37)、PlanStep(60) | 97 | 是 |
| `engine.flow.lifecycle` | DefaultTaskLifecycle(180)、TaskLifecycle(26) | 206 | 是 |
| `engine.flow.graph` | DefaultEffectHandler(512)、DefaultGraphKernel(108)、DefaultGraphTransitionReducer(314)、DefaultNodeRunner(508)、DefaultNodeStateMachine(52)、EffectHandler(26)、GraphEffect(105)、GraphKernel(25)、GraphResumeActions(64)、GraphTransitionReducer(28)、NodeRunner(28)、NodeStateMachine(26)、NodeToolset(152)、NodeTransition(30)、WriteVerifier(186) | 2164 | 是 |
| `domain.graph` | EdgeType(23)、GraphFrame(234)、GraphStep(48)、NodeEvent(68)、NodeStatus(191)、TaskEdge(70)、TaskGraph(425)、TaskNode(131) | 1190 | 是 |
| `engine` | DefaultTurnPipeline(285)、TurnContext(105)、TurnDecision(122)、TurnPipeline(22)、TurnRequest(81)、TurnResult(115) | 730 | 是 |

第二遍带注释复核的类：`TurnSessionHydrate`、`TurnFlushPlan`、`EvidenceFlow`、`DefaultRuntimeStateFacade`、`RuntimeStateFacade`、`DefaultTurnScope`、`RuntimeStatePersistence`、`WriteVerifier`、`DefaultNodeRunner`、`NodeBuiltinTools`、`ControlBuiltinTools`、`DefaultTaskLifecycle`、`RuntimeProperties.State`，以及 7 个 package-info。

补读（链路追踪用）：`DefaultTurnEntryGuard`、`ContractValidator`（verify 部分）、`DefaultTurnDispatcher`（决策部分）、`TurnScene`、`ConversationServiceImpl`（加锁、收尾、删除会话）、`DefaultReActLoop`、`StallTracker`、`ArgsBrief`、`JsonClip`、`renew.lua`、`flush.lua`、`application.yml`（state 配置）。

### 1.2 v3 补审（手写代码，逐类读完）

| 包 | 类 |
|---|---|
| `engine.flow.react` | ReactProjectionFactory、ReactProjection、DefaultPromptAssembler、ToolCatalogProjector、DefaultPhaseHint、PromptAssembleRequest（DefaultReActLoop、StallTracker 已在 v1 读完） |
| `engine.flow.decision` | 全部 10 个类 |
| `engine.flow.entry / hydrate / execute / finalize` | 全部类 |
| `engine.model` | DefaultModelGateway、ModelGateway、ModelUse |
| `service.impl` | ConversationServiceImpl、SseEventSink、ChatConversationServiceImpl 与 TaskInstanceServiceImpl 的手写方法；Mapper XML 中的 `advanceStateVersion` 与 `updateBody` |
| `controller` | ConversationController；生成的 CRUD Controller 抽读 ToolCallRecordController、TaskInstanceController，并核对了全部 11 个 `@RequestMapping` |
| `event` | StepEvents、DefaultEventPublisher、TurnEvents |
| `config / client` | McpClientConfig、ModelClientConfig、RuntimeConfig、AgentProfiles、AgentProfileClient；`application.yml` |
| `support` | RuntimeUtils、JsonSchemaSupport、PlatformCatalog、FailureCategory、ToolCallbacks、TraceScope |
| `api.spi` | 全部类 |

### 1.3 未审计

- i18n 文案、`support.trace` 的事件实现、`OriginResolver`、`RedisKeys`；
- `domain.*` 与 `api.dto / vo / enums / spec` 的数据结构（只在调用处按需查看）；
- 生成代码（DAO、PO、Converter，以及 CRUD Service/Controller 的模板部分）：按约定不评审写法，只在 P1-8 中评估其暴露面；
- g2rain-common 中 `JsonCodec`、`DistributedLock` 的实现（`lookupNode` 对缺失路径返回 null，已由 `SuccessRuleTest` 印证）；
- 未运行测试，未在 Redis Cluster 下验证 Lua。

## 二、数据流追踪

| 状态 | 写入方 | Redis | MySQL | 回灌 | 读取方 | 问题 |
|---|---|---|---|---|---|---|
| 会话栅栏 | `renew.lua`、`TurnFlushPlan.writeFence` | `state` Hash（会话 TTL 7d） | `chat_conversation.state_version`（persist CAS） | Redis 缺失取 MySQL 版本 | 每次 flush 比对 | 中断只靠 `lastTraceId` 判定（P1-4） |
| 任务组 | `TurnFlushPlan.writeTask` | 元数据为哨兵（TTL 比组内短 `sentinel-gap`），其余 key 同 TTL | `task_instance` 等 | **逐 key**：Redis 无则取 MySQL | 入口闸门、内核 | 哨兵只用于 TTL，回灌没有按哨兵定组（P1-3） |
| 断点 | `DefaultEffectHandler.saveSuspend` | `checkpoint`（无断点即 DEL） | `task_checkpoint`（消费时逻辑删除） | Redis 无 key 即取 MySQL | `DefaultTurnEntryGuard.isSuspended`：有断点即视为挂起 | DEL 与过期不可区分（P1-3） |
| 证据头 | `EvidenceFlow.prewrite/settle/record/applyOutcome` | `node:<nid>:evidence` | `tool_call_record` | `openSpace`：Redis 空才取 MySQL | `EvidenceReader`、`FinishPolicy`、`WriteGate` | 中断规整只改 Redis 中的 `STARTED`（P1-4）；超大报文 `payload_id` 未落库（P2-4） |
| 工具报文 | `EvidenceFlow.persistPayload`（Javadoc：Redis 始终写完整报文） | `payload:<eid>` | `response` 或 `task_node_payload` | `RuntimeStateFacade.payload` | `EvidenceReader`、`builtin_evidence_get`、`SourceVerifier` | 与 `flush-max-bytes` 冲突（P1-2）；读取不限作用域（P1-1） |
| 节点便签 | `DefaultNodeScope.note` | `space.state`（`Map<String,Object>`） | `task_graph_node.state` | `HashCodec.toObject` | 内置工具、拦截器 | 类型往返丢失（P2-8） |
| 任务记录 | `TaskRecords.of`（两次） | `records` | `task_instance.task_record` | Redis 空取 MySQL | 前情、意图 | `closedAt` 两处不一致（P2-5） |
| 恢复审计 | `DefaultTurnScope.resume`（轮级字段） | — | `task_resume_record`、`task_checkpoint` 逻辑删除 | — | 审计 | 换任务时按新任务 ID 消费（P2-1） |

## 三、发现

### P1

#### P1-1 证据读取与证据来源核验不限作用域

- **位置**：`NodeBuiltinTools.evidenceGet`；`SourceVerifier.verify` 的 `EVIDENCE` 分支 → `EvidenceReader.matches`；底层 `DefaultRuntimeStateFacade.payload`。
- **现状**：`payload()` 依次读本轮内存 → 本任务 Redis → `CallRepository.response(evidenceId)` → `PayloadRepository.loadArtifact(evidenceId)`，后两步只按 ID 查，租户拦截器只加 `organ_id`。模型给出同组织任一 evidenceId，就能读到其他会话、其他用户的完整报文（未脱敏），也能在 `builtin_output_set` 里引用它，通过来源核验。
- **依据**：CONTRACT_SPEC 来源定义中 `EVIDENCE` 是“本节点或任一上游节点（沿依赖边追溯，含委派）某条成功证据”；STATE_SPEC 5.6 也只允许读前置节点证据。`ArgBinding`、`SourceResolver` 走的 `successfulEvidenceNewestFirst` 守住了这个范围，`output_set` 和 `evidence_get` 这两个入口没有守。
- **利用条件**：需要知道 UUID，模型正常只看到本会话的 ID，所以定为 P1，不是 P0。

#### P1-2 “Redis 始终写完整报文”与 flush 总量上限冲突

- **位置**：`EvidenceFlow.persistPayload`（Javadoc：Redis 始终写完整报文）；`TurnFlushPlan.writePayload`、`apply`；配置 `payload-inline-max-bytes=512KB`、`flush-max-bytes=1MB`。
- **现状**：报文与状态改动在同一批 ops 里计算大小。单个报文转义后超过约 1MB，或几份大报文叠加后超过，`settle` 里的 flush 就抛 `STATE_WRITE_FAILED`，此时工具已经执行了。`ConversationServiceImpl.runTurn` 收尾时又 flush 一次，报文还在 `turn.payloads()` 里，再次失败，persist 被跳过，失败回复也没有落库。写工具在下一轮进入 VERIFY；读工具查询同样的大结果，每次都会失败。
- **依据**：STATE_SPEC 5.10 给大报文单独设了 MySQL 通道，说明设计上允许大报文存在；`flush-max-bytes` 的本意是防止状态膨胀，不是限制报文大小。

#### P1-3 任务组逐 key 回灌，已消费的断点会复活

- **位置**：`TurnSessionHydrate.load`（checkpoint、shared、prior、graph 逐 key 回落 MySQL）；`TurnFlushPlan.writeTask` 无断点时 `DEL checkpoint`；`DefaultTurnEntryGuard.isSuspended`。
- **现状**：断点在轮内被消费，flush 把 Redis 里的 key 删掉。若本轮 persist 两次失败（STATE_SPEC 4⑥ 允许这种情况，下一轮按中断处理），MySQL 中的 `task_checkpoint` 不会被逻辑删除。下一轮 Redis 读不到 → 回落 MySQL → 旧断点复活。而 `isSuspended` 只要断点存在就判定为挂起，于是入口闸门按这个已处理过的断点放行 CONFIRM/REJECT，用户会再次看到已处理过的审批卡。写操作去重索引能挡住重复派发，但流程已经错了。
- **依据**：STATE_SPEC 1.2「Redis 为准」；2.1 元数据是任务组的「哨兵」，TTL 比组内其他 key 短 `sentinel-gap`，本意就是“哨兵还在，组内 key 一定还在”。但回灌代码没有用到这层语义，这是第 8 节约束 2 的直接后果。

#### P1-4 中断规整只改 Redis 中的 STARTED，冷恢复路径会让写操作卡住

- **位置**：`TurnSessionHydrate.tidyInterrupt`、`rewriteStarted`。
- **现状**：`rewriteStarted` 只读 Redis 证据 Hash，且只在任务为 `RUNNING` 时执行；`lastTraceId` 只存在 Redis。下面三种路径都会漏掉 MySQL 中的 `STARTED` 写证据：
  1. 某轮崩溃后，用户超过任务 TTL（24h）才回来：会话 `state`（TTL 7d）里还记着 `lastTraceId`，但任务组 key 已过期；
  2. 审批确认后派发写调用时崩溃，任务组也已过期：MySQL 中任务仍是上次落库的 `SUSPENDED`，规整被跳过；
  3. Redis 重启或主从切换后会话 `state` 丢失：`lastTraceId` 跟着丢失，根本识别不出中断。

  这些证据经 `CallRepository.loadNodeEvidence` 原样回灌后，同一写调用永远是 `WRITE_IN_FLIGHT`（DEFERRED，只能等轮次上限兜底），`FinishPolicy` 拒绝完成，`WriteVerifier` 只处理 `UNKNOWN`，节点会一直耗到失败。
- **依据**：STATE_SPEC 1.2「Redis 失效后……保证可以继续对话、不重复执行写操作」；5.2 `STARTED` 改为 `UNKNOWN`。

#### P1-5 依赖故障被当作“没有技能”，续跑节点可能空跑并被判为成功

- **位置**：`AgentProfiles.loadCapabilityBundle`（调用失败或返回不成功时给 null）；`DefaultBundleLoader`（契约非法的技能只打日志后丢弃）；`DefaultHydrator`（不判空）；`DefaultTurnPipeline.doRun`（装载后只检查 `isFailed`）。
- **现状**：开新任务时，技能为空会让规划失败，这条路径没问题。问题出在 `CONTINUE_TASK`：续跑沿用已有图，不经过规划。如果此时 agent-studio 短暂不可用，或者节点用到的技能契约刚被改坏，`frame.findSkill` 返回 null，节点就在没有契约的情况下运行。`NodeToolset` 只提供内置工具，`DefaultFinishPolicy` 在“无契约、无业务工具”时直接放行完成，模型调用 `finish` 后，任务被判为 `SUCCEEDED`，实际什么都没做。
- **依据**：CONTRACT_SPEC 第 16 节「数字员工没有任何可用 Skill 时，规划失败」，意图是装载失败要失败关闭，但续跑路径没有覆盖到。
- **修复方向**：`AgentProfiles` 区分“调用失败”和“确实为空”，前者抛 `DEPENDENCY_UNAVAILABLE`；续跑时，若图中节点引用的技能不在本轮装载结果里，本轮直接失败，不进入执行。

#### P1-6 模型调用不设读超时，上游挂起会把会话永久锁死

- **位置**：`application.yml` 的 `model.read-timeout-ms: 0`、`stream.timeout: 0`；`ModelClientConfig`；`ConversationServiceImpl.send`（`waitLockWithWatchdog`）。
- **现状**：模型网关或模型服务一旦挂起，本轮会无限等待；看门狗持续给会话锁续期，同一会话的后续请求全部被拒，只能靠重启实例恢复。SSE 也不会超时。MCP 调用有 300 秒上限，模型调用却没有。
- **依据**：商用要求所有外部调用都有上限。MCP 侧“超时后转回查”的设计说明项目本身也采用这一原则。
- **修复方向**：按 `ModelUse` 分别设置读超时（ReAct 和规划可以长一些，意图和收口校验短一些），超时归为 `MODEL_TIMEOUT`（`FailureCategory` 已经有这个分类）；另外给整轮设一个总时长上限。

#### P1-7 提示词超长时从尾部整块静默丢弃，节点内历史没有预算

- **位置**：`DefaultPromptAssembler.projectUser`；`react.workspace-slice-max-chars: 8000`；`DefaultReActLoop` 中的 `history`。
- **现状**：
  1. 各投影块依次拼接，总长超过 8000 字符时从尾部整块丢弃，直到只剩一块，全程没有日志。`node_goal` 排第一块，里面包含技能手册和数字员工画像，手册稍长，`tool_catalog`、`evidence_digest`、`output_schema`、`prior` 就会全部被丢掉，模型看不到契约和证据，系统也察觉不到。
  2. 节点内 `history` 只增不减：最多 32 轮、每条观测最多 8000 字符，再加上跨轮重建的历史，很容易超出模型上下文，导致调用失败或成本失控。
- **修复方向**：按优先级逐块裁剪，每块保留摘要而不是整块丢弃，裁剪时记 Trace；`node_goal` 中的手册单独设上限；history 按预算从最早的轮次开始压缩。

#### P1-8 生成的 CRUD 接口暴露了运行态表的写入和全量查询（需确认网关权限）

- **位置**：`controller` 下 11 个生成的 Controller（`/task_checkpoint`、`/tool_call_record`、`/task_instance`、`/task_node_payload`、`/chat_message` 等），都提供 `save`、`delete`、`selectList`、`selectPage`。
- **现状**：这些接口只受租户拦截器（organ 级）约束，不校验会话归属。如果网关没有把它们限定给管理端，会有三个后果：
  1. 同组织的任何用户都能读取他人的对话、工具报文和任务；
  2. 可以改写 `task_checkpoint.pending_action`，等 Redis 任务组过期后，这条记录会被回灌，再发一次 CONFIRM 就能执行。派发走 `approvedWrite`，会跳过参数、来源、守卫三道校验，等于用伪造的参数执行写工具；
  3. 可以改写 `tool_call_record` 的状态，绕过写操作去重。
- **说明**：这是生成代码，按约定不评审写法；这里评估的是部署暴露面。如果网关已经只对管理端开放这些路径，可降为 P2，并建议只保留 agent-app 任务追踪实际用到的查询接口。

### P2

| # | 位置 | 现状 | 依据 / 修复 |
|---|---|---|---|
| P2-1 | `DefaultTaskLifecycle.prepare`、`DefaultTurnScope.start`、`RuntimeStatePersistence.persistTurn` | 恢复审计是轮级字段（注释：persist 时写入 resume 表），`start()` 不随旧任务转出；`REPLACE_TASK`/`SWITCH_AGENT` 时按新任务 ID 消费，查不到就返回，旧任务断点不逻辑删除，也没有恢复记录 | STATE_SPEC 5.3「保证每次恢复都有记录」。旧任务已终态、不会再被读取，所以只影响审计，定 P2。恢复字段合并为带 `taskId` 的 `ResumeMark`，随 `ClosedTask` 一起落库 |
| P2-2 | `DefaultRuntimeStateFacade.persist`、`TurnFlushPlan.dropClosedTask` | 删除任务组与清空 `activeTaskId` 分两次 flush，中间崩溃后 `load` 会读回终态任务当作活动任务，之后 `start()` 会再次落库并重复推入 `records` | 删除 ops 并入最后一次 `apply(turn, true)`；`load` 读到终态任务时清空 `activeTaskId` |
| P2-3 | `DefaultNodeRunner.proceed` | 失败、取消节点的 `errorCode` 写的是状态名 `FAILED`/`CANCELLED`，真实错误码丢失 | state-audit-fixes 4.12「取消不是失败，error_code 为空」；D4 要求写节点错误码。`ReActLoopResult` 带出 `RuntimeErrorCode` |
| P2-4 | `EvidenceFlow.record`、`settle` | 超大报文的 `payloadId` 只写进 Redis 证据头，`tool_call_record.payload_id` 为空 | STATE_SPEC 5.10「证据头记录 `payloadId`」。落库使用带 `payloadId` 的证据头 |
| P2-5 | `RuntimeStatePersistence.recordOf`、`DefaultRuntimeStateFacade.persist` | 同一任务的 `TaskRecord` 生成两次，`closedAt` 不同 | 只生成一次，经 scope 传给 `dropClosedTask` |
| P2-6 | `DefaultRuntimeStateFacade.persistOnce` | 重试时 `EntityExtractor.collect` 再跑一次，`mentions` 重复累加 | `collect` 移到重试外 |
| P2-7 | `NodeFinisher.finish` | 先 `applyFinish` 再 `captureOutputs`，后者抛 `PLATFORM_FIELD_FORBIDDEN`（非 `ToolRejectedException`）时，节点已 DONE、观测却是失败，共享值可能只写了一半 | CONTRACT_SPEC「通过完成判定后节点 DONE，写入共享值和实体」。先校验，再一并写入 |
| P2-8 | `NodeSpace.state`、`settleDelegateAwaiting`、`HaltResolver` | 便签为 `Map<String,Object>`，`DelegateResult`、`PendingAction`、`List<EntitySeed>` 往返后变成 Map，读取靠 `PendingAction.from`、`String.valueOf`、`obj2str` 兜底 | 编码规范「领域状态必须使用明确类型」。`NodeNoteKey` 绑定值类型，`note` 读取时按类型转换 |
| P2-9 | `DefaultNodeRunner.dispatchPending` | `argsHash` 拿同一对象的 args 与自身 hash 比对，恒相等 | STATE_SPEC 3.2「执行前校验参数未被改动」。需要先确定比对对象（见第五节问题 3） |
| P2-10 | `ControlBuiltinTools.skip` | `builtin_node_skip` 不在 CONTRACT_SPEC 内置工具表中；不检查 `LOOKUP_PENDING`，而 finish、fail、follow_up 都检查 | 补文档；加 `HaltResolver.requireNoLookup` |
| P2-11 | `TaskGraph.resumeAwaiting` | 直接 `setStatus(FAILED)`，错误码、文案为硬编码字面量，绕过 `turn.mark` | 返回损坏节点，由调用方 `mark`，使用 `RuntimeErrorCode.DELEGATE_BROKEN` |
| P2-12 | `NodeBuiltinTools`、`ControlBuiltinTools`、`OutputContractValidator`、`ProviderNodes` | 异常参数和标题拼接中文硬编码 | 改为 ErrorCode 命名参数与 i18n 文案 |
| P2-13 | `InputBinder`、`ProviderNodes.alreadyBound`、`WriteVerifier.verifyArgs`；`EvidenceFlow.liveKey` | `session.`/`shared.`/`prior.`/`outputs.`/`call.` 前缀散落解析；回落幂等键与 `IdempotencyKeyBuilder` 重复实现 | 统一走 `ValueRef.parse`；`liveKey` 调用 `IdempotencyKeyBuilder` |
| P2-14 | `DefaultNodeRunner.applyVerifyResume`、`HaltResolver.lastCall*` | 一次 CONFIRM 认定节点内全部 `UNKNOWN` 写证据；`applyOutcome` 把旧证据移到列表末尾，`getLast()` 不再是“最后一次调用” | “最后一次”按 `round` 取；VERIFY 断点记录对应 evidenceId |
| P2-15 | 其他 | `EnvBuiltinTools` 无 `@Component` 也未注册（死代码）；`NodeToolset.of` 在每次节点执行、派发、回查时都调 MCP `listTools`；`DefaultReActLoop` 畸形调用分支不计无进展；`McpToolCallbackProvider` 重试日志计数错位；STATE_SPEC 列出的 `LREM` 未实现、`tokens` 仍写着“当前未统计”；`RuntimeStateFacade.open` 的 Javadoc 写“处理 UNKNOWN 证据”，实际只处理 `UNKNOWN_PRE_CALL` | 逐项修正或同步文档 |
| P2-16 | `TaskInstanceMapper.advanceStateVersion` | 更新语句不含 `skill_versions`，首轮插入之后才锁定的技能版本不会落库，冷恢复时丢失 | STATE_SPEC 3.2 要求该字段落库到 `task_instance.skill_versions`。SQL 中补上该列 |
| P2-17 | `ConversationRepository.touch` | 为了更新 `last_message_time`，读出整行再全量回写，`state_version`、`active_task_id`、`session_shared` 一并被写回；`updateBody` 不校验 `version`，存在丢失更新的窗口 | 改为只更新 `last_message_time` 的专用语句 |
| P2-18 | `NodeToolset.of` | 注册中心按 `allowed_tools` 元数据过滤后返回工具，客户端没有再按 `toolNames` 校验一遍 | CONTRACT_SPEC 1.3「可调用范围以 toolNames 为准」。客户端补一层过滤，作为纵深防御 |
| P2-19 | `ReactProjectionFactory.nodeGoal` | `platform`（organId、userId 等）原样投影给模型。平台参数由 ArgBinding 注入，模型不需要看到这些值 | state-audit-fixes 4.6「对外文案不暴露平台注入字段」。只投影字段名 |
| P2-20 | `SseEventSink.register` | `streamId` 在请求线程生成，回调在虚拟线程里又调用一次 `requireTraceId`；网关未注入 traceId 时两次 UUID 不同，事件推不到前端 | 只生成一次，作为参数传入 |
| P2-21 | 配置 | `react.repair-max-rounds`、`goal-gate.llm-enabled`、`goal-gate.min-deterministic-chars` 没有任何代码读取 | 删除，或补上实现 |
| P2-22 | `DefaultModelGateway.exchange` | 5xx（504 除外）和连接异常都会整包重发；ReAct 请求体大，可能重复计费；重试没有退避 | 只对建连失败重试，并加退避 |

### 设计建议（不是缺陷：代码与文档一致，但存在风险）

| # | 内容 | 现行依据 | 建议 |
|---|---|---|---|
| D-1 | 参数未声明 `entity` 时，`EVIDENCE` 按“抽取内容中某个值完全相等”认定，小整数 ID（如 `1`）容易撞上 `total`、`page` 之类的字段 | CONTRACT_SPEC 来源定义明文如此；`entity` 是精确模式 | SKILL_GUIDE 增加一条“应当”：写工具的 ID 参数配置 `entity`；`ContractValidator` 可对未配置的情况记告警 |
| D-2 | 回查工具的结论完全依赖它自己的 `success` 规则；没配 `success` 时，查询成功（包括查不到）都会把写证据认定为成功 | WriteVerifier Javadoc 与 SKILL_GUIDE W2（“应当”：回查工具的 success 必须能在查不到时判失败）；装载校验第 8 条没有覆盖 | 装载校验增加一条：`verify.tool` 必须配置 `success` |
| D-3 | 子节点 DONE 后，可以再次委派同一技能、同一目标 | CONTRACT_SPEC 15「重复」只拦截失败、取消、跳过 | 是否也拦截 DONE，由你决定 |
| D-4 | 节点历史里的调用参数是脱敏、截断后的 `argsBrief`，模型可能照抄 `***` 或截断后的值 | STATE_SPEC 3.3 没有规定历史参数的口径 | 在历史中标明这是参数摘要，或明确口径 |
| D-5 | 业务工具没有在 `contract.tools` 中配置时，按只读处理：不经过写闸门、不预登记、不做幂等 | CONTRACT_SPEC 1.3 明确规定“未配置按默认规则（只读）” | 商用建议改为失败关闭：未配置的工具按写工具处理，或读取 MCP 工具注解（`readOnlyHint`、`destructiveHint`）来判定 |
| D-6 | 没有按用户或组织限制并发和调用频率，每一轮都会调用多次模型 | — | 在网关或 runtime 入口加限流与配额 |

## 四、v1 → v2 复核变更

| v1 | v2 | 理由 |
|---|---|---|
| P1-5 换任务断点未消费 | 降为 P2-1 | 旧任务已终态，不会再被读取，只影响审计 |
| P1-6 回查成功即认定生效 | 降为设计建议 D-2 | Javadoc 与 SKILL_GUIDE W2 明确由回查工具的 `success` 判定“查无”，v1 的修复方向（看 `empty`）与设计相悖；真正的缺口是装载校验没有强制 |
| P1-7 值相等认定来源 | 撤回，改为 D-1 | CONTRACT_SPEC 明文定义了该语义，`entity` 是现成的精确模式 |
| P2-4 DEFERRED 无上界 | 撤回 | TOOL_OBSERVATION 第 3 节明文「不计也不清零，由总轮次上限兜底」 |
| P2-18 `discard` 留下任务组 key | 撤回 | `discard` 的 Javadoc 明文按任务 TTL 自然过期 |
| P2-10 重复委派 DONE 目标 | 改为 D-3 | 与 CONTRACT_SPEC 15 一致 |
| P1-4 | 补充触发路径 | 原先只写了 Redis 丢失；复核发现任务组过期、任务状态为 `SUSPENDED` 时同样触发，并且更常见 |
| P1-3 | 补充影响 | 读了 `DefaultTurnEntryGuard.isSuspended`，确认复活的断点会被当作挂起处理 |

## 五、P1 修复设计（待你确认后实施）

设计遵循项目已有的思路：Redis 为准、哨兵定组、栅栏保护写入、证据可达范围沿依赖边追溯、不做兼容；不新增层次，只修正现有实现。

### 5.1 证据可达范围（P1-1）

- `EvidenceReader` 新增 `reachable(ctx, evidenceId)`：判定口径与 `successfulEvidenceNewestFirst` 相同（本节点 + `TaskGraph.upstream`）。
- `builtin_evidence_get` 与 `SourceVerifier` 的 `EVIDENCE` 分支先调用它，不可达时抛 `ToolRejectedException`（复用 `EVIDENCE_PATH_INVALID`，或新增 `EVIDENCE_UNREACHABLE`）。
- `RuntimeStateFacade.payload` 保持为内部读取。

### 5.2 报文不受状态上限约束（P1-2）

保留“Redis 始终写完整报文”的设计，只把报文从状态上限里拆出来：

- `TurnFlushPlan.ops` 把报文 SET 和状态改动分成两组，`flush-max-bytes` 只校验状态组，两组仍在同一次 Lua 调用中提交，栅栏语义不变。
- 另一条路线是超阈值报文不进 Redis、只走 MySQL，但这会改变“Redis 始终写完整报文”的设计，需要你拍板（第六节问题 1）。

### 5.3 哨兵定组的回灌与冷恢复规整（P1-3、P1-4）

- **回灌口径**：元数据（哨兵）在 Redis 中 → 组内 key 只读 Redis，缺失即为空；哨兵缺失 → 整组从 MySQL 回灌，记为冷恢复。
- **规整口径**：`lastTraceId` 非空，或本轮为冷恢复时执行：
  - 任务为 `RUNNING` 时，`RUNNING` 节点 → `READY`；
  - 不论任务状态，所有节点的 `STARTED` 写证据 → `UNKNOWN`，并改从 `openSpace(...).evidence()` 读取（这条路径已带 MySQL 回落）。
- **文档**：同步修改 STATE_SPEC 5.2 与第 8 节约束 2。
- **已知边界**：新任务首轮就崩溃、且任务组已过期时，MySQL 中没有 `task_instance`，`STARTED` 证据挂在已丢失的 taskId 上，按任务维度的去重挡不住，用户重试会再写一次。要覆盖这种情况，需要让 S2 预登记先落 `task_instance`，改动较大，单独讨论。

### 5.4 补审 P1 的修复方向（P1-5～P1-8）

- **P1-5**：依赖调用失败时抛错，不再当作空结果处理；续跑前校验图中节点引用的技能都已装载。
- **P1-6**：按 `ModelUse` 设置读超时，再设整轮总时长上限。
- **P1-7**：投影按块裁剪并记录 Trace；history 设预算。
- **P1-8**：先确认网关权限，再决定是否下线生成的写接口。

## 六、需要你决定的问题

1. P1-2 走哪条路线：报文拆出上限（保留现有设计），还是超阈值报文不进 Redis？
2. 5.3 的“已知边界”是否要在本次一并处理？
3. P2-9 的 `argsHash` 应与什么比对：审批卡展示时的参数，还是直接删去这项校验、改文档？

## 七、提示词审计（support.prompt）

**范围**：`IntentPrompt`、`SkillCandidatePrompt`、`PlanPrompt`、`ReactPrompt`、`GoalGatePrompt` 全文，以及各自的输入组装方：`DefaultTurnDispatcher`/`IntentRequest`、`SkillSelector`、`PlanDrafter`、`ReactProjectionFactory`、`GoalEvidenceAssembler`/`DefaultGoalGate`、`RequestLocale`。

**方法**：逐条核对三件事——提示词里引用的字段和工具，组装方是否真的提供；提示词描述的规则，与运行时代码的实际行为是否一致；输入里有没有外部可控文本，会不会被当成指令执行。

**与代码一致的部分**（不再展开）：五个提示词的 `%s` 占位数量与 `formatted` 参数一一对应，正文中没有游离的 `%`；意图场景的可选模式与 `TurnScene` 一致；ReAct 的审批流程、完成与跳过规则、来源说明，与 `WriteGateInterceptor`、`DefaultFinishPolicy`、`NodeToolset` 一致；GoalGate 的“查询为空如实告知视为达成”与 state-audit-fixes 4.5 一致。

| # | 提示词 | 问题 | 依据 / 修复 |
|---|---|---|---|
| PR-1 | PlanPrompt | 提示词说 `continuitySummary` 是“上一个任务的综述，接续前情时让新计划接上已有结论”，但 `PlanDrafter.planMemoryOf` 传入的是 `Prior.reason`，而 `DefaultTaskLifecycle.priorOf` 把它固定写成字面量 `"start"`。规划模型实际拿不到任何前情，`START_WITH_CONTEXT` 在规划这一层不起作用（节点层的 `<prior>` 块正常） | 用前情中最近任务的 goal/summary/request 组装 `continuitySummary`；`Prior.reason` 的取值改为枚举或删除 |
| PR-2 | GoalGatePrompt | `GoalGateView` 把每个节点的 `result` 截到 `prompt-max-chars`（500 字符）。多节点任务走模型核验时，提示词要求“优先使用节点的 result”整理最终答复，结果截断后最终答复也会缺内容，长列表还可能被模型补写 | 截断时显式标注被省略，并要求模型不得补全；或按节点数分配预算。单节点任务走 `adoptable`，不受影响 |
| PR-3 | GoalGatePrompt | 提示词把 `evidence` 定义为“最近几次成功调用”，但 `GoalEvidenceAssembler.calls` 只过滤 `status == SUCCESS`，会把 `businessOk=false` 的业务失败调用也当成功证据交给核验模型 | 过滤条件改用 `EvidenceHead.done()` |
| PR-4 | ReactPrompt | 优先级规则 2 把“平台给出的下一步”放在最高优先级，但失败观测的原因文本里夹带上游系统原文（`ResponseContractInterceptor.reasonOf` 取业务报文的 message，`MCP_TOOL_ERROR` 取工具错误文本）。上游文本若含“下一步：…”，模型无法分辨哪个才是平台的。规则 3 只约束了“成功返回的数据”，没有覆盖这种情况 | 观测中把上游原文放进有界字段（如加引号并标为“业务返回”），规则 3 扩展到失败原因中的上游原文；平台“下一步”使用上游文本无法伪造的固定标记 |
| PR-5 | 全部（`%s` 语言标签） | `RequestLocale.tag()` 原样返回 `Accept-Language`，可能是 `zh-CN,zh;q=0.9,en;q=0.8` 这种列表，而且由客户端控制，被直接拼进 system 提示词 | 用 `Locale.LanguageRange.parse` 取第一项再 `toLanguageTag()`，非法值回退默认值（如 `PrincipalContextHolder` 已做规范化，可降级） |
| PR-6 | IntentPrompt | WRITE_PENDING 规则把“要求修改待确认的操作”判为 `CONTINUE_TASK`，但 `settle` 会把它统一改成 `UNCERTAIN`，回复固定为“请确认是否继续执行”。用户想改参数，得到的却是确认提示，文字通道没有修改路径（INTENT_ROUTING 的 D2 设计只覆盖了“同意执行”） | 提示词去掉“修改”这一项，或为修改单独设计路径（例如按 REJECT 处理后带着修改意见重跑节点），由你决定 |
| PR-7 | ReactPrompt | `<checkpoint>` 块说明“仍在等待用户回复的断点，存在时先按其中说明处理”，但断点在节点唤醒时已被消费（KNOWN_ISSUES F13），ReAct 期间 `turn.checkpoint()` 恒为空，这个块和这条说明实际不会出现 | 删除该块与说明，或改为投影 `resumed()`（这需要先确认你的意图） |
| PR-8 | SkillCandidatePrompt | 没有规定“没有合适技能”时输出什么。模型返回空数组时，`SkillSelector` 返回空列表导致规划失败；解析失败或空响应时却回退全量。同一种“没选出来”有两种结果 | 提示词写明无关时输出空数组，代码对空数组与回退的处理统一 |
| PR-9 | IntentPrompt / IntentRequest | `IntentRequest.recentEpisodes` 恒为 null，提示词也未引用，属于死字段 | 删除 |
| PR-10 | ReactPrompt | `node_goal.platform` 把平台身份值发给模型，又要求模型“不出现平台注入的字段及其值”，与其事后约束，不如不发（同 P2-19） | 只投影字段名 |

**提示词层面的结论**：结构清晰，与运行时规则高度对齐，没有发现会直接导致错误写操作的问题。需要优先处理的是 PR-1（规划拿不到前情）、PR-2（最终答复可能被截断）、PR-4（失败原因中的注入路径）。
