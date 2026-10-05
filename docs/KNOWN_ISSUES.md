# 已知问题清单

来源：2026-10-05 CMS 端到端联调（Apifox，8 轮对话：查结构 → 建站点 → 建空间 → 写文章 → 存草稿 → 确认）。

状态说明：**已修复** 已改代码或种子数据并编译通过，但未做回归测试；**待处理** 只记录，尚未动手。

---

## 一、待处理

### Q0 主线被拆成多个任务，每步完成即关闭【严重 · 设计】

| 项 | 内容 |
|---|---|
| 现象 | 用户的一件连续的事（看结构 → 建站点 → 建空间 → 写文章 → 确认）被拆成多个任务，每个任务只有 1 个节点：eea15990（查结构）、9ff771a3（建站点）、e8ac883e（建空间）、25d18032（写文章），做完一步就 `SUCCEEDED` 关闭，下一步再新开任务 |
| 根因 | 三处叠加：① Agent 的 `principle_desc` / `mission` 写着"只规划本轮立即要做的能力；同句里的后续意图不提前规划"，所以 Planner 每次只生成 1 个节点；② `DefaultGraphTransitionReducer.whenIdle` 在所有节点都 DONE 时直接 `SUCCEEDED` 收口，任务关闭并清空 `activeTaskId`；③ 下一条消息 `hasActiveTask=false`，意图分类提示词的规则 3 不允许 CONTINUE_TASK，只能 START_FRESH / START_WITH_CONTEXT |
| 影响 | 跨任务引用的 ID 拿不到可信来源（Q2）、上下文只能靠前情摘要衔接、证据和节点历史按任务隔离，用户补充的信息容易断开；这几轮多数"反复确认""上下文断裂"都与此相关 |
| 方向 | 重新定义任务的生命周期：节点全部完成后，任务是否进入"等待下一指令"而不是立即关闭，何时才算真正结束（用户换话题、显式结束、超时）；同时调整规划和意图分类，让同一主线的后续诉求走 CONTINUE_TASK、往现有任务图里追加节点。Q2 的处理方式取决于本条结论 |

### Q1 控流动作没有统一的准入依据【设计】

| 项 | 内容 |
|---|---|
| 现象 | 第 2 轮建站点、第 4 轮建空间时，模型不调写工具，直接用 `builtin_node_follow_up` 口头确认（"是否确认创建""类型是否为 WEBSITE"），返回 REPLY/CANCEL 而不是审批卡 |
| 根因 | 业务工具每次调用都经过拦截器裁决，控流工具却各管各的：finish 有 FinishPolicy / doneWhen，approval 有写闸记录（方案 A），follow_up 没有任何依据 |
| 已尝试 | 在手册和 Agent 原则里写"禁止口头确认""中文枚举直接映射"，提示词已确认加载，Qwen3.8-27B 仍然不遵守 |
| 方向 | 给每个控流动作定义 runtime 认可的依据：追问需要"契约判定缺参"或"证据里有多个候选"；审批需要"写闸拦下的调用"；完成需要"doneWhen 满足"。放在同一层统一裁决，立住以后撤掉第三节的补丁规则 |

### Q2 跨任务实体引用没有走前情通道【设计】

| 项 | 内容 |
|---|---|
| 现象 | 新任务里引用上一任务建的空间（"在这个网站下……""刚建的空间"），模型填了旧 ID，被判为编造 |
| 根因 | 所有 extracts 都没配 `entity`，前情（Prior）里没有实体；证据校验只看当前任务 |
| 现状 | 把 ID 参数改成只认 `EVIDENCE`，逼模型在本任务重查一次。能跑通，但这是绕路 |
| 方向 | 查询类 extracts 配 `entity`；参数通过 `inputs.*.from: "prior.<type>"` 或 `PRIOR_TASK` 来源接前情。要先确认前情袋的键（`type:id`）与参数路径怎么对应（`WorkingTree.priorBag`、`InputBinder.priorValue`、`SourceVerifier` 的 PRIOR_TASK 分支） |

### Q3 文章必须有分类，与契约、手册冲突【业务，需决策】

| 项 | 内容 |
|---|---|
| 现象 | 第 8 轮 CONFIRM 后，`post_article_save` 已经派发，CMS 返回 `system.50001`：`Column 'category_id' cannot be null`；之后模型补救失败，最终"连续无进展" |
| 根因 | CMS 文章表 `category_id` 不允许为空；而接口 schema 和契约里 `categoryId` 是可选参数，11002 手册写的是"分类不是写文章的前提" |
| 待定 | 文章是否必须属于某个分类？不必须：改 CMS，允许为空或给默认分类；必须：契约里 `categoryId` 改为必填（缺失时先调 `get_article_category_list`），手册改为"空间下没有分类时先建分类再写文章" |

### Q4 已批准的写操作业务失败后，没有收口路径【设计】

| 项 | 内容 |
|---|---|
| 现象 | 第 8 轮确认后写入失败，模型反复重试写工具，每次都要重新审批；中间又去建分类、申请审批，最后停滞失败 |
| 根因 | 没有规定"已批准的写操作业务失败"之后节点该怎么办：报告用户、失败收口，还是允许补救后重新审批 |
| 方向 | 和 Q1 一起设计：已批准写失败时把错误回给用户，节点收口（FAILED，或挂起 FOLLOW_UP 让用户决定），不允许模型自行反复试 |

### Q5 审批被拒时的提示有误导性【代码】

| 项 | 内容 |
|---|---|
| 位置 | `ControlBuiltinTools.gatedWrite` → `APPROVAL_NOT_GATED` |
| 现象 | 实际被闸门拦下的是 `post_article_category_save`，模型申请审批时工具名填成了 `post_article_save`；返回的提示是"须先调用写工具 [post_article_save]" |
| 方向 | 有拦截记录但工具名不一致时，提示里给出实际被拦下的工具名 |

### Q6 `builtin_output_set` 的错误文案占位符没有填值【代码】

| 项 | 内容 |
|---|---|
| 现象 | 返回 `来源校验失败：{0}` |
| 方向 | 补上参数，或改用无参文案 |

### Q7 执行器模型的指令遵循能力【配置】

| 项 | 内容 |
|---|---|
| 现象 | Q1 中，提示词里写明的禁止规则不稳定地被遵守；第 7 轮也出现过把用户给的正文当成没给 |
| 方向 | Q1 落地后再评估；必要时给执行器换指令遵循更强的模型（model-nexus 配置） |

### Q8 意图分类偶发空响应【缓解】

| 项 | 内容 |
|---|---|
| 现象 | Qwen3.8-27B 偶尔第一个 token 就是结束符（今天约 1.5%），分类结果为 null，返回"是否交给其他数字员工" |
| 现状 | 已加空响应重试一次（见 F3） |
| 方向 | 可选：通过 model-nexus 透传 `response_format: json_schema` 或 guided decoding，从解码层保证输出 JSON |

### Q9 种子数据与接口定义的零散问题【数据】

- 11004 的 skill_tool_binding 没有绑定 `get_article_list`，打标时无法查文章，`articleId` 只能保留 `USER` 来源。
- mcp-registry 中状态枚举不一致：站点 update_status 写 `ACTIVE/INACTIVE`，站点 body 写 `ENABLED/DISABLED`；栏目写 `ACTIVE/INACTIVE`。所以契约里站点和栏目状态没配 `enum`。

### Q10 没有回归测试【工程】

- `g2rain-agent-runtime-biz/src/test` 为空。本清单 F1–F5 的修复都只编译通过，没有单元测试保护。

### Q11 仍会偶发"是否交给其他数字员工"【待复现】

| 项 | 内容 |
|---|---|
| 现象 | 加了 F3 的空响应重试后，联调中仍偶尔返回"您是想继续刚才的事、换一件新事，还是交给其他数字员工？"；当前日志中没有留下 UNCERTAIN 记录，原因尚未定位 |
| 可能原因 | ① 重试后仍为空；② 模型返回了非 JSON 内容，解析失败后返回 null（这种情况不重试）；③ 模型自己判成 UNCERTAIN；④ 降级文案本身有问题：所有 UNCERTAIN 都用同一句 `ROUTE_UNCERTAIN`，没有活动任务、或只有一个候选数字员工时也会问"换数字员工" |
| 复现时抓取 | 日志中的 `[IntentAudit] ... mode=UNCERTAIN`、`[IntentClassifier] empty reply`；同一 traceId 在 model-nexus 日志中的分类请求和返回内容 |
| 方向 | 定位后再处理。降级文案应按上下文区分：没有活动任务时不提"继续刚才的事"，候选只有一个时不提"换数字员工" |

---

## 二、已修复（未回归测试）

| # | 问题 | 改动 |
|---|---|---|
| F1 | 模型直接调 `builtin_node_approval`，绕过写工具的参数校验 | 方案 A：`WriteGateInterceptor` 拦下时记录待批调用（`NodeNoteKey.GATED_WRITE`，绑定后参数和摘要）；`ControlBuiltinTools.approval` 只接受被拦下、且工具名一致的调用，去掉 `arguments` 参数；新增错误码 `APPROVAL_NOT_GATED`（42245） |
| F2 | 用户补参后续跑，已回答的 FOLLOW_UP 断点仍投影在 `<repair>` 里，诱导模型重复追问 | `ReactProjectionFactory.repair()` 不再投影 FOLLOW_UP 断点 |
| F3 | 意图分类空响应直接降级为 UNCERTAIN | `DefaultIntentClassifier` 空正文重试一次（`MAX_ATTEMPTS = 2`） |
| F4 | 追问问题只在工具参数里，节点历史里没有，续跑时问答断开 | `DefaultReActLoop` 在节点以 FOLLOW_UP 挂起退出、且模型那步正文为空时，把追问问题补记为本节点 AGENT 消息（`appendNodeReply`） |
| F5 | 拒绝编造值时一律挂 PROVENANCE_REJECT 义务，把提示里要求先调用的查询工具也拦掉 | `ParamContractInterceptor.syntheticObligation`：契约要求先查证据时挂 CALL_TOOL 义务 |
| F6 | 契约 `from: shared.*` 没有任何写入方、每个工具复制整块 args、可选参数配 missing 等 | 按 SKILL_AUTHORING_GUIDE 重写 11001–11004 的 `skill_contract`（studio 种子 SQL） |

---

## 三、为绕过问题加的补丁规则（Q1/Q2 落地后复查、撤销）

| 位置 | 内容 | 对应问题 |
|---|---|---|
| agent_profile `principle_desc` | "已给齐的字段直接调用写工具，被要求确认时再申请审批；写操作的确认由审批卡完成，不用 builtin_node_follow_up 口头确认" | Q1 |
| agent_profile `style_desc` | "中文类型、状态按系统枚举值映射，无需确认" | Q1 |
| 11001 手册 | "禁止用 builtin_node_follow_up 口头确认"、"中文类型、状态直接映射为枚举值" | Q1 |
| 11002 / 11003 手册 | "中文状态、内容类型直接映射为枚举值" | Q1 |
| 11003 / 11004 手册 | "业务字段已齐就直接调用写工具……禁止用 builtin_node_follow_up 口头确认" | Q1 |
| 11001–11004 契约 | 31 个 ID 参数的 `sources` 改为只填 `["EVIDENCE"]` | Q2 |
