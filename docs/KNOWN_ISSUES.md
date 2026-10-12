# 已知问题清单

来源：2026-10-05 CMS 端到端联调（Apifox，8 轮对话：查结构 → 建站点 → 建空间 → 写文章 → 存草稿 → 确认）。

状态说明：**已修复** 已改代码或种子数据并编译通过，但未做回归测试；**待处理** 只记录，尚未动手。

---

## 一、待处理

### Q8 意图分类偶发空响应【缓解】

| 项 | 内容 |
|---|---|
| 现象 | Qwen3.8-27B 偶尔第一个 token 就是结束符（今天约 1.5%），分类结果为 null，返回"是否交给其他数字员工" |
| 现状 | 空响应直接返回 null，由调度器按场景兜底。2026-10-12 去掉了空响应重试（F3）：重试解决不了根因 |
| 方向 | 可选：通过 model-nexus 透传 `response_format: json_schema` 或 guided decoding，从解码层保证输出 JSON |

### Q10 没有回归测试【工程】

- 本清单 F1–F6 的修复都只编译通过，没有单元测试保护；`src/test` 目前只覆盖 F7（意图路由）。

## 二、已修复（未回归测试）

| # | 问题 | 改动 |
|---|---|---|
| F1 | 模型直接调 `builtin_node_approval`，绕过写工具的参数校验 | 方案 A：`WriteGateInterceptor` 拦下时记录待批调用（`NodeNoteKey.GATED_WRITE`，绑定后参数和摘要）；`ControlBuiltinTools.approval` 只接受被拦下、且工具名一致的调用，去掉 `arguments` 参数；新增错误码 `APPROVAL_NOT_GATED`（42245） |
| F2 | 用户补参后续跑，已回答的 FOLLOW_UP 断点仍投影在 `<repair>` 里，诱导模型重复追问 | `ReactProjectionFactory.repair()` 不再投影 FOLLOW_UP 断点。2026-10-11 R2 后 `<repair>` 块已删除，等待中的断点改由 `<checkpoint>` 块投影 |
| F3 | 意图分类空响应直接降级为 UNCERTAIN | `DefaultIntentClassifier` 空正文重试一次（`MAX_ATTEMPTS = 2`）。2026-10-12 已撤销：重试解决不了根因，空响应交调度器兜底，根因随 Q8 处理 |
| F4 | 追问问题只在工具参数里，节点历史里没有，续跑时问答断开 | `DefaultReActLoop` 在节点以 FOLLOW_UP 挂起退出、且模型那步正文为空时，把追问问题补记为本节点 AGENT 消息（`appendNodeReply`）。已由 F14 的节点动作记录取代 |
| F5 | 拒绝编造值时一律挂 PROVENANCE_REJECT 义务，把提示里要求先调用的查询工具也拦掉 | `ParamContractInterceptor.syntheticObligation`：契约要求先查证据时挂 CALL_TOOL 义务。2026-10-11 R2 删除补参义务后不再适用：拒绝观测的"下一步"直接给出先调的查询工具 |
| F6 | 契约 `from: shared.*` 没有任何写入方、每个工具复制整块 args、可选参数配 missing 等 | 按 SKILL_AUTHORING_GUIDE 重写 11001–11004 的 `skill_contract`（studio 种子 SQL） |
| F7 | 新对话、补参回复等场景偶发"是否交给其他数字员工"（原 Q11） | 意图路由重构，见 [INTENT_ROUTING.md](INTENT_ROUTING.md)：补参在入口按 REPLY 续跑；意图模型按场景判断且不输出 UNCERTAIN；分类失败按场景兜底并记录原因；该文案仅用于待确认写操作 |
| F8 | 写操作被闸门拦下要求确认后，模型反复调用写工具直至停滞失败；申请确认时工具名不一致，提示给的是错误工具名（原 Q5） | 工具结果改为四种结果（SUCCESS/FAILED/REJECTED/DEFERRED），见 [TOOL_OBSERVATION.md](TOOL_OBSERVATION.md)：待确认计为 DEFERRED，不计无进展；提示写明下一步（调用 `builtin_node_approval` 及工具名）；工具名不一致时返回 `APPROVAL_TOOL_MISMATCH`（42246）并给出实际被拦下的工具名 |
| F9 | 查询成功后模型反复写非必填产出 `builtin_output_set`，拒绝只返回"来源校验失败：{key}"（原 Q6），连续无进展失败 | 非成功观测强制"原因。下一步：…"，错误码改为 `RuntimeErrorCode` 枚举，内置工具拒绝改抛 `ToolRejectedException`；`PhaseHint` 只按契约必填产出提示。见 [TOOL_OBSERVATION.md](TOOL_OBSERVATION.md) 第 7 节 |
| F10 | 文章必须有分类，与契约、手册冲突（原 Q3）：确认写文章后 CMS 报 `category_id` 不能为空，模型临时去建分类，又要确认一次 | 定为必须有分类：11002 契约 `post_article_save.categoryId` 改为必填，缺失时先调 `get_article_category_list`；手册改为未指定分类时追问选哪个，没有分类时追问是否新建；CMS `ArticleServiceImpl.save` 新增前校验 `spaceId`、`categoryId`，返回 `PARAM_REQUIRED` 而不是数据库异常 |
| F11 | 已批准的写操作业务失败后，模型自行补救、反复申请确认（原 Q4） | 已批准写操作执行失败时，`WriteGateInterceptor.after` 把下一步换成向用户说明并追问如何处理，用户不再继续时 node_fail；见 [TOOL_OBSERVATION.md](TOOL_OBSERVATION.md) 7.3 |
| F12 | `RuntimeErrorCode` 中约 60 处 `{0}` 占位符从不被替换（`MessageResolver` 只认 `{0:name}`），模型收到"来源校验失败：{0}" | 统一改为 `{0:message}` / `{0:detail}` |
| F13 | 已回应的断点在续跑整轮仍被当作"等待中"投影给模型；续跑后再次挂起时漏写 `task_resume_record` | 节点唤醒时即消费断点（`turn.resumed()` 保留现场供派发、核实、认定追问字段）；去掉 FOLLOW_UP 投影过滤与完成时的 ClearSuspend。见 STATE_SPEC 5.3 |
| F14 | 跨轮重建节点历史有损：工具结果只有 200 字摘要；内置工具调用、被拒或推迟的调用、用户确认都不在历史里 | 节点空间新增 `actions` 记录无证据动作；证据头新增 `detail` 保存未成功时的完整说明；重建时成功结果读完整报文。追问问题由 follow_up 动作记录，去掉原 F4 的补记。见 STATE_SPEC 3.3 |
| F15 | 控流动作没有统一的准入依据（原 Q1）：模型用 `builtin_node_follow_up` 口头确认写操作，绕过审批卡 | 三个控流动作由 runtime 裁决：审批须有写闸记录（L12）；完成由 FinishPolicy；追问须有依据（`ControlBuiltinTools.requireGrounded`），否则 `FOLLOW_UP_UNGROUNDED`；第三节相关提示词补丁已撤。枚举口头确认残留不再单独跟踪 |

---

## 三、为绕过问题加的补丁规则（Q1/Q2 落地后复查、撤销）

| 位置 | 内容 | 对应问题 |
|---|---|---|
| agent_profile `principle_desc` | "已给齐的字段直接调用写工具，被要求确认时再申请审批；写操作的确认由审批卡完成，不用 builtin_node_follow_up 口头确认" | Q1（2026-10-12 已撤，由 L13 通用规则与 L14 追问准入承担） |
| agent_profile `style_desc` | "中文类型、状态按系统枚举值映射，无需确认" | Q1（2026-10-12 已撤，验证枚举确认是否复现） |
| 11001 手册 | "禁止用 builtin_node_follow_up 口头确认"（2026-10-12 已撤）、"中文类型、状态直接映射为枚举值"（2026-10-12 已撤） | Q1 |
| 11002 / 11003 手册 | "中文状态、内容类型直接映射为枚举值" | Q1（2026-10-12 已撤） |
| 11002 / 11003 / 11004 手册 | "业务字段已齐就直接调用写工具……禁止用 builtin_node_follow_up 口头确认"、11002 "不得再让用户口头确认""审批卡就是确认入口" | Q1（2026-10-12 已撤） |
| 11001–11004 契约 | ID 参数的 `sources` 曾改为只填 `["EVIDENCE"]`；Q2 修复后已加上 `PRIOR_TASK`，跨任务引用不再需要本任务重查 | Q2（已处理） |

---

## 四、已关闭

| # | 问题 | 结论 |
|---|---|---|
| Q0 | 主线被拆成多个任务，每步完成即关闭 | 2026-10-12 关闭：按设计保留"一步一任务"，跨任务衔接由前情通道承担（见 Q2）。联调多轮未再因拆分任务出现上下文断裂或失败，不改任务生命周期 |
| Q2 | 跨任务实体引用没有走前情通道 | 2026-10-12 联调验证通过：前情实体带 `id` 与类型别名，模型填的前情 ID 按 `matchesPriorId` 认定为 `PRIOR_TASK`；保存工具只回主键时由 `entity.scalar` 把新建对象沉淀为实体（L7）。联调中建栏目直接使用前两个任务新建的站点、空间 ID，写文章、打标签直接使用前情里的分类、文章 ID，未再出现 `PARAM_PROVENANCE_REJECTED`；前情同时保留来自多个任务的实体 |
| Q7 | 执行器模型的指令遵循能力 | 2026-10-12 关闭：属模型能力/配置选择，不按 runtime 联调缺陷跟踪；需要时通过 model-nexus 换更强指令遵循模型 |
| Q9 | 种子数据与接口定义的零散问题 | 2026-10-12 关闭：11004 绑定 `get_article_list` 已修；mcp-registry 状态枚举不一致属临时接口定义，后续以库表数据为准并上 CRUD/导入后会替换，不再按联调问题跟踪 |
