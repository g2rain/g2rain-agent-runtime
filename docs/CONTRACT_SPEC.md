# g2rain-agent-runtime Skill Contract 规范

本文档定义 Skill 的组成和 `skill_contract` 的全部属性：每个属性的含义、取值、默认值、运行时由谁使用、违反时会发生什么。内容以当前代码实现为准。

按本规范编写 Skill 的操作步骤、模板和检查清单见 [SKILL_AUTHORING_GUIDE.md](SKILL_GUIDE.md)。

---

## 目录

1. [Skill 的组成](#1-skill-的组成)
2. [契约结构总览](#2-契约结构总览)
3. [根属性](#3-根属性)
4. [tools：工具契约](#4-tools工具契约)
5. [args：参数契约](#5-args参数契约)
6. [success / error / extracts / verify](#6-success--error--extracts--verify)
7. [inputs：节点输入](#7-inputs节点输入)
8. [outputs：节点产出](#8-outputs节点产出)
9. [取值表达式](#9-取值表达式)
10. [doneWhen：完成条件](#10-donewhen完成条件)
11. [平台字段](#11-平台字段)
12. [内置工具](#12-内置工具)
13. [工具调用处理链](#13-工具调用处理链)
14. [写工具：审批、幂等与核实](#14-写工具审批幂等与核实)
15. [委派](#15-委派)
16. [装载校验](#16-装载校验)
17. [错误码](#17-错误码)
18. [枚举汇总](#18-枚举汇总)
19. [解析规则与未生效属性](#19-解析规则与未生效属性)

---

## 1. Skill 的组成

### 1.1 数据来源

Skill 配置在 agent-studio 的四张表中，Runtime 每轮通过 studio 接口加载。

| 表 | 关键字段 | 作用 |
|---|---|---|
| `agent_profile` | `agent_code`、`agent_name`、`identity_desc`、`mission_desc`、`boundary_desc`、`style_desc`、`principle_desc`、`status` | 数字员工：身份、职责、边界、风格、原则 |
| `skill_profile` | `skill_key`、`skill_name`、`semantic_desc`、`skill_manual`、`skill_contract`、`status` | 技能：说明、操作手册、契约 |
| `agent_skill_binding` | `agent_id`、`skill_id`、`enabled_flag` | 数字员工拥有哪些技能 |
| `skill_tool_binding` | `skill_id`、`tool_name`、`enabled_flag` | 技能可以调用哪些业务工具（即 `toolNames`） |

只有 `status=ACTIVE` 的 Skill、`enabled_flag=1` 的绑定会被加载。

### 1.2 各字段的用途

| 字段 | 用在哪里 | 写作要求 |
|---|---|---|
| `agent_profile.*_desc` | 路由时用 `mission_desc`（为空用 `identity_desc`）选数字员工；选定后五段描述进入规划和节点提示词 | 写清职责边界，不写能力清单 |
| `skill_key` | 技能唯一标识；规划、委派、`inputs.provider` 都用它引用技能 | 小写、点分，如 `cms.article.lifecycle` |
| `skill_name` | `semantic_desc` 为空时作为技能短描述 | 简短名称 |
| `semantic_desc` | 意图判定的技能目录、规划阶段的候选筛选、前置节点标题 | 一段话说明"适用于什么、不负责什么"，供模型选择技能 |
| `skill_manual` | 规划出图的提示词、节点执行的提示词（`node_goal.skillManual`） | 目标、业务规则、前置与产出，供模型执行时遵守 |
| `skill_contract` | Runtime 的确定性约束：参数从哪来、何时算成功、何时可完成 | 本规范的主体 |
| `toolNames` | 节点可调用的业务工具集合 | 只写业务工具，不写内置工具 |

### 1.3 toolNames 与 contract.tools 的关系

| 规则 | 说明 |
|---|---|
| 可调用范围以 `toolNames` 为准 | 节点可见工具 = 全部内置工具 + `toolNames` 中的业务工具。调用其他工具返回 `CATALOG_DENIED` |
| `contract.tools` 是可选配置 | 只对 key 与工具名完全相等的工具生效；未配置的工具仍可调用，按默认规则处理（只读、无业务判定、无参数约束） |
| 提示词中的工具目录 | 只列出内置工具和在 `contract.tools` 中配置过的业务工具。未配置的工具模型仍能看到函数定义，但没有契约说明 |

建议：`toolNames` 中的每个业务工具都在 `contract.tools` 中配置。

---

## 2. 契约结构总览

```text
skill_contract
├── mode                    执行模式
├── delegatable             能否被委派
├── target                  主业务工具
├── doneWhen                完成条件
├── inputs.<name>           节点输入
│   ├── from
│   ├── required
│   └── provider
├── outputs.<name>          节点产出
│   ├── type / fields / minItems / hint
│   ├── required
│   ├── share
│   ├── entity { type, id, name }
│   ├── sources
│   ├── constant
│   └── sensitive
└── tools.<toolName>        工具契约
    ├── label
    ├── sideEffect
    ├── confirm
    ├── idempotencyKey[]
    ├── args.<paramPath>    参数契约
    │   ├── from / const / when
    │   ├── sources
    │   ├── required
    │   ├── enum
    │   ├── missing { action, tool, message }
    │   ├── sensitive
    │   ├── label
    │   └── fresh
    ├── success { path, op, value }
    ├── error { code, message }
    ├── extracts[] { key, path, fields, entity }
    └── verify { tool, args }
```

---

## 3. 根属性

| 属性 | 类型 | 默认 | 含义 | 运行时行为 |
|---|---|---|---|---|
| `mode` | `REACT` / `PLAN` | `REACT` | 执行模式 | 规划时候选技能只有一个且为 `REACT` → 跳过大模型规划，直接生成单节点图；否则由大模型规划 |
| `delegatable` | boolean | `false` | 能否被其他节点委派调用 | 为 `true` 时进入同一数字员工的委派目录；要求 `tools` 中没有写工具 |
| `target` | String | 无 | 主业务工具名 | 投影进节点提示词；未配置 `doneWhen` 时，完成要求该工具成功 |
| `doneWhen` | String | 无 | 完成条件表达式 | 装载时解析，模型调用 `builtin_node_finish` 时求值（见第 10 节）；不出现在提示词中 |
| `inputs` | Map | `{}` | 节点开始时绑定的输入 | 见第 7 节 |
| `outputs` | Map | `{}` | 节点允许写入的产出 | 见第 8 节 |
| `tools` | Map | `{}` | 各业务工具的契约 | 见第 4 节 |

---

## 4. tools：工具契约

key 为工具名，必须与 `toolNames` 中的名称完全一致。

| 属性 | 类型 | 默认 | 含义 | 运行时行为 |
|---|---|---|---|---|
| `label` | String | 无 | 工具展示名 | 工具开始时作为过程提示下发给客户端（为空时依次取 MCP 工具标题、"正在处理"）；投影进提示词的工具目录 |
| `sideEffect` | `NONE` / `WRITE` | `NONE` | 是否有副作用 | `WRITE` 时启用审批、预登记、去重、结算（见第 14 节） |
| `confirm` | Boolean | `WRITE` 为 `true`，`NONE` 为 `false` | 是否需要用户确认 | 只能在 `WRITE` 上配置为 `true`。设为 `false` 还需同时加入配置 `state.write-confirm-bypass-tools` 才真正免审批 |
| `idempotencyKey` | List<参数路径> | `[]` | 幂等键由哪些参数组成 | 用于判断"是不是同一个写操作"（见 14.2） |
| `args` | Map | `{}` | 参数契约 | 见第 5 节 |
| `success` | Object | 无 | 业务成功条件 | 见 6.1。`WRITE` 工具必须配置 |
| `error` | Object | 无 | 业务失败时错误码、错误信息所在路径 | 见 6.2 |
| `extracts` | List | `[]` | 从结果中抽取的语义片段 | 见 6.3 |
| `verify` | Object | 无 | 写结果不确定时的回查方式 | 见 6.4 |

---

## 5. args：参数契约

### 5.1 参数路径

key 为参数路径，点分写法，`[]` 表示数组中的每一项：

| 写法 | 含义 |
|---|---|
| `title` | 顶层参数 |
| `orderInfo.taxNo` | 嵌套参数 |
| `lines[].goodsCode` | 数组每一项的字段 |

只有在 `args` 中列出的参数受契约约束；工具 schema 中有、但 `args` 中没列的参数，由模型自由填写。

### 5.2 属性

| 属性 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `from` | String | 无 | 取值表达式（见第 9 节）。取到值时**总是覆盖**模型给的值。与 `const` 互斥 |
| `const` | JSON | 无 | 常量，总是覆盖模型给的值。与 `from` 互斥 |
| `when` | `ALWAYS` / `UNIQUE` | `ALWAYS` | `from` 的绑定条件（见 5.4） |
| `sources` | List<SourceType> | `[]` | 没有 `from`/`const` 时，参数值允许的来源（见 5.3） |
| `required` | Boolean | `true` | 是否必填。缺值时按 `missing` 处理；为 `false` 时缺值直接放行 |
| `enum` | List<String> | `[]` | 允许的取值。不匹配时转为向用户追问 |
| `missing` | Object | `{action: ASK_USER}` | 必填参数缺值时的处理（见 5.5） |
| `sensitive` | boolean | `false` | 敏感参数：在参数摘要、审批文案中打码 |
| `label` | String | 无 | 参数展示名：用于审批文案（"标签：值"）、追问字段名、工具目录 |
| `fresh` | Boolean | 无 | 当前未生效 |

### 5.3 参数值的判定流程

Runtime 按以下顺序处理每个列在 `args` 中的参数（`ParamContractInterceptor`）：

**第一步：绑定**

- 有 `const` → 写入常量，来源记为 `CONTRACT`，结束。
- 有 `from` 且取到值 → 覆盖模型的值，来源记为表达式对应的类型（见第 9 节）。

**第二步：缺值处理**

参数没有值时：

- `required=false` → 放行；
- 否则按 `missing.action` 处理。

**第三步：来源判定**

参数有值时，判断它的可信来源：

| 情况 | 判定 |
|---|---|
| 有 `from`/`const`，且第一步绑定成功 | 绑定的来源 |
| 有 `from`/`const`，但绑定没取到值（值来自模型） | `MODEL` |
| 没有 `from`/`const` | 按 `sources` 顺序逐个匹配，见下表；都不匹配则为 `MODEL` |

| sources 中的值 | 匹配条件 |
|---|---|
| `USER` | 当前追问断点的 `expectedFields` 包含该参数；或最近对话中某条用户消息（归一化、去空白、小写后）包含该值（值长度 ≥ 2） |
| `EVIDENCE` | 值与本节点（或委派前置节点）某条成功证据的抽取内容中的某个值完全相等 |
| `NODE_OUTPUT` / `SHARED` / `SESSION` / `PRIOR_TASK` | 以参数路径为键，在对应数据中查到相同的值 |
| `PLATFORM` / `CONTRACT` | 不会匹配（这两类来源只能通过 `from`/`const` 产生） |
| `MODEL` | 总是允许 |

**第四步：拒绝模型编造**

判定结果为 `MODEL`，且 `sources` 不包含 `MODEL` 时：

- `sources` 包含 `USER` → 转为向用户追问；
- 否则拒绝调用，返回 `PARAM_PROVENANCE_REJECTED`，并挂上补参义务。

**第五步：枚举校验**

配置了 `enum` 且值不在其中 → 转为向用户追问。

由此得出的写法规则：

| 想要的效果 | 写法 |
|---|---|
| 参数由平台、上游、共享值确定，模型不能改 | `from` |
| 参数固定 | `const` |
| 优先自动绑定，取不到时允许模型填写 | `from` + `sources: ["MODEL"]` |
| 必须是用户说过的值 | `sources: ["USER"]` |
| 必须来自工具查询结果 | `sources: ["EVIDENCE"]`，可配合 `missing: CALL_TOOL` |
| 模型可以自由填写 | 不在 `args` 中列出，或 `sources: ["MODEL"]` |

注意：

- 列在 `args` 中、没有 `from`/`const`、`sources` 为空的参数，模型的值总会被拒绝。
- `from` + `sources: ["USER"]`（不含 `MODEL`）在绑定失败时会反复追问，因为追问得到的值仍然来自模型。

### 5.4 when：绑定条件

| 值 | 含义 |
|---|---|
| `ALWAYS` | 取到值就绑定 |
| `UNIQUE` | 只在候选唯一时绑定：`evidence.*` 表达式要求取到的值是长度为 1 的数组（绑定的是数组本身）；其他表达式要求路径中第一个 `[` 之前的数组长度为 1 |

数组模板：参数路径和 `from` 都包含 `[]` 时，按下标逐项填充，只填模型没给值的下标。`evidence.*` 不支持数组模板。

### 5.5 missing：缺值处理

| 属性 | 默认 | 含义 |
|---|---|---|
| `action` | `ASK_USER` | 缺值时的动作 |
| `tool` | 无 | `action=CALL_TOOL` 时先调用的工具。必须在 `toolNames` 中，且不能是参数所属的工具本身 |
| `message` | 无 | `action=FAIL` 时的失败说明 |

| action | 行为 | 返回码 | 挂上的补参义务 |
|---|---|---|---|
| `ASK_USER` | 拒绝本次调用，要求模型向用户追问 | `PARAM_PROVENANCE_ASK_USER` | `ASK_USER_REQUIRED` |
| `CALL_TOOL` | 拒绝本次调用，要求先调用 `tool` 获取数据 | `PARAM_PROVENANCE_CALL_TOOL` | `CALL_TOOL_REQUIRED` |
| `FAIL` | 拒绝本次调用，只允许节点失败 | `PARAM_PROVENANCE_FAIL` | `PLATFORM_CONTEXT_MISSING` |

多个参数同时有问题时，处理优先级为：立即拒绝 > `FAIL` > `CALL_TOOL` > `ASK_USER`。

`missing` 只对 `required=true` 的参数生效。`action=CALL_TOOL` 但 `tool` 为空时，缺值会被放行。

---

## 6. success / error / extracts / verify

### 6.1 success：业务成功条件

| 属性 | 类型 | 含义 |
|---|---|---|
| `path` | String | 响应 JSON 中的点分路径 |
| `op` | SuccessOp | 比较方式（必填） |
| `value` | JSON | 期望值；`IN` / `NOT_IN` 时为数组 |

| op | 判定 |
|---|---|
| `EQ` | 相等。两边都能转成数字时按数值比较（`200` 等于 `"200"`），否则按字符串比较 |
| `NE` | 不相等 |
| `IN` | 属于 `value` 数组 |
| `NOT_IN` | 不属于 `value` 数组 |
| `TRUTHY` | 非 null、非 false、非 0、非空串、非 `"0"` / `"false"`、非空容器 |
| `EXISTS` | 路径存在（值为 null 也算） |

规则：

- 配置了 `success` 而响应为空 → 业务失败。
- 未配置 `success` → 只要调用层不报错即视为成功，不做业务判定。
- 业务失败时，证据状态为 `SUCCESS`、`businessOk=false`，模型收到失败观测。

### 6.2 error：错误信息路径

| 属性 | 含义 | 取不到时 |
|---|---|---|
| `code` | 业务错误码所在路径 | 使用 `TOOL_BUSINESS_FAILED` |
| `message` | 错误信息所在路径 | 使用整个响应 JSON（截断） |

### 6.3 extracts：语义抽取

| 属性 | 必填 | 含义 |
|---|---|---|
| `key` | 建议 | 抽取项名称，供 `evidence.<key>` 表达式和 `evidence.<key> exists` 条件引用；为空时以 `path` 计数 |
| `path` | 是 | 响应中的路径，如 `data`、`data.records` |
| `fields` | 否 | 用于计数：`path` 指向对象时，任一字段有值算 1 条 |
| `entity` | 否 | 实体识别：`{type, id, name}`。对 `path` 下的对象或数组每一项生成实体；`id` 字段默认 `id`，`name` 字段默认 `name` |

抽取结果的用途：

| 用途 | 说明 |
|---|---|
| 条数 | `path` 指向数组时为数组长度，指向对象时为 0 或 1 |
| 空结果 | 所有抽取项条数都为 0 时，证据 `empty=true`；没有 `extracts` 时按响应结构判断是否为空 |
| 取值 | `evidence.<key>.<路径>` 在 `path` 子树上取值 |
| 实体 | 写入会话实体索引，可在后续任务通过前情引用 |

### 6.4 verify：写结果回查

写工具结果不确定（超时、网络异常等）时，用一个只读工具回查是否已生效。

| 属性 | 含义 |
|---|---|
| `tool` | 回查工具名。必须在 `contract.tools` 中、`sideEffect=NONE`，并且在 `toolNames` 中 |
| `args.<k>` | 回查参数。只支持 `const` 和 `from: "call.<路径>"`（取原写操作的请求参数）；为空时把原请求整体传入 |

回查结果按回查工具自身的 `success` 判定：成功 → 写操作记为成功；失败 → 记为失败。因此回查工具在"查不到记录"时必须判定为业务失败（通过 `success` 配置），否则会被误判为写成功。

---

## 7. inputs：节点输入

节点开始执行时，按 `inputs` 绑定节点输入，只补不覆盖。

| 属性 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `from` | String | 无 | 取值表达式 |
| `required` | Boolean | `true` | 取不到值时，在提示词中标记为缺失（不阻止执行） |
| `provider` | String | 无 | 提供该输入的技能 `skill_key`。规划时会在本节点前插入一个前置节点执行该技能 |

`from` 支持的写法：

| 写法 | 取值 |
|---|---|
| `session.<key>` | 会话级共享值 |
| `shared.<key>` | 任务级共享值 |
| `outputs.<key>` | 上游已完成节点的产出 |
| `prior.<type>` | 前情中第一个该类型实体的展示名 |
| `prior.<type>.<attr>` | 前情中第一个该类型实体的属性 |
| 不写或其他前缀 | 取最近一个已完成上游节点的同名产出 |

`provider` 规则：

- 不能是自身，必须属于同一数字员工。
- `from` 为 `shared.` / `session.` 且值已存在时，不插入前置节点。
- 前置节点声明的所有共享产出都已存在时，该节点在执行时直接跳过（`SKIPPED`）。

---

## 8. outputs：节点产出

节点产出由模型调用 `builtin_output_set` 写入，是节点的正式结果，可被下游节点、共享值和实体索引使用。声明了 `outputs` 后，只允许写入声明过的键。

| 属性 | 类型 | 默认 | 含义 | 运行时行为 |
|---|---|---|---|---|
| `type` | `VALUE` / `LIST` | `VALUE` | 值类型 | `LIST` 必须配置 `fields`；投影进提示词 |
| `fields` | List<String> | `[]` | 列表项字段 | 投影进提示词 |
| `minItems` | int | 1 | 最少条数 | 投影进提示词 |
| `hint` | String | 无 | 写入说明 | 投影进提示词 |
| `required` | Boolean | `true` | 完成前是否必须写入 | 完成时检查：必须已写入、非空，且来源合法 |
| `share` | `TASK` / `SESSION` | 无 | 共享范围 | 节点完成时写入 `shared.<key>` 或 `session.<key>` |
| `entity` | Object | 无 | 实体识别 `{type, id, name}` | 节点完成时生成实体：值为对象时取 `id`/`name` 字段（默认字段名 `id`、`name`），为标量时取值本身 |
| `sources` | List<SourceType> | `[]` | 允许的来源 | 写入和完成时校验；为空表示除 `MODEL` 外都允许；`MODEL` 永远不允许 |
| `constant` | JSON | 无 | 常量 | 来源为 `CONTRACT` 时，值必须等于它 |
| `sensitive` | boolean | `false` | — | 当前未生效 |

约束：

- 产出的 key 不能与平台字段同名（如 `userId`、`organId`、`name`，见第 11 节）。
- 共享值只能通过 `share` 写入，这是 `shared.*`、`session.*` 表达式取值的唯一来源。

---

## 9. 取值表达式

`args.<p>.from` 使用以下前缀：

| 前缀 | 取值来源 | 记录的来源类型 |
|---|---|---|
| `inputs.<路径>` | 本节点已绑定的输入 | `NODE_OUTPUT` |
| `outputs.<路径>` | 本节点已写入的产出 | `NODE_OUTPUT` |
| `shared.<路径>` | 任务级共享值 | `SHARED` |
| `session.<路径>` | 会话级共享值 | `SESSION` |
| `prior.<type:id>.<attr>` | 前情实体，键为 `type:id` | `PRIOR_TASK` |
| `platform.<字段>` | 本轮登录身份（见第 11 节） | `PLATFORM` |
| `evidence.<key>[.<路径>]` | 证据抽取内容 | `EVIDENCE` |

`evidence.<key>[.<路径>]` 的解析过程：

1. 取本节点及委派前置节点的成功证据（业务成功，包括空结果），较新的在前。
2. 在证据所属工具的 `extracts` 中找到 `key` 相同的抽取项。
3. 在该抽取项的 `path` 子树上按 `<路径>` 取值。

`key` 取到第一个 `.` 为止，所以数组下标要写在 key 之后，例如 `evidence.tag_list.0.id`（取第一个元素的 id）。

`call.<路径>` 只用于 `verify.args`，表示原写操作的请求参数。

未知前缀会使整个 Skill 加载失败。

---

## 10. doneWhen：完成条件

### 10.1 语法

```bnf
expr    ::= and ( " OR " and )*
and     ::= atom ( " AND " atom )*
atom    ::= 下表中的任一形式
NAME    ::= [a-zA-Z][a-zA-Z0-9_]*
TOOL    ::= [a-zA-Z0-9_:.-]+
```

- `AND` 优先于 `OR`；不支持括号；关键字不区分大小写。
- 每个条件必须完整匹配下表的某种形式，否则 Skill 加载失败。

| 条件 | 含义 |
|---|---|
| `TOOL succeeded` | 本节点该工具最新一条证据"算成功"；或任一委派前置节点有该工具的成功证据 |
| `TOOL empty` | 本节点该工具最新一条证据调用成功且结果为空 |
| `target succeeded` | `target` 指定的工具"算成功"（未配置 `target` 时不满足） |
| `evidence.TOOL succeeded` | 同 `TOOL succeeded` |
| `evidence.NAME exists` | 本节点有一条"算成功"的证据，其工具名为 NAME，或其抽取项中包含 NAME |
| `outputs.NAME exists` | 产出已写入且非空 |
| `outputs.NAME length >= N` | 产出是数组，且长度 ≥ N |
| `outputs.NAME[*].FIELD exists` | 产出是非空数组，且每一项的 FIELD 都非空 |

**"算成功"**：证据状态为 `SUCCESS`、业务成功、**且结果非空**。

因此查询类工具在"查不到"也应算完成时，要写成 `TOOL succeeded OR TOOL empty`。

### 10.2 完成判定顺序

模型调用 `builtin_node_finish` 时，按以下顺序检查，任一不满足即拒绝完成，节点继续执行：

| 顺序 | 检查 | 不满足时的提示 |
|---|---|---|
| 1 | `finish` 的 `output` 参数非空 | `FINISH_MISSING_OUTPUT` |
| 2 | 没有未结算的写操作（`STARTED` / `UNKNOWN` / `UNKNOWN_PRE_CALL`） | `FINISH_OPEN_EVIDENCE` |
| 3 | 没有未满足的补参义务 | `FINISH_REPAIR_PENDING` |
| 4 | 所有 `required=true` 的产出已写入，来源合法 | `FINISH_OUTPUT_*` |
| 5 | 配置了 `doneWhen` → 只看 `doneWhen` | 对应条件的提示，如 `FINISH_EVIDENCE_TOOL`、`FINISH_OUTPUT_MISSING`；多个 OR 分支都不满足时为 `FINISH_OR_FAILED` |
| 6 | 未配置 `doneWhen`、配置了 `target` → `target` 必须"算成功" | `FINISH_NEED_TARGET` |
| 7 | 都没配置、没有声明产出、`toolNames` 非空 → 至少一个业务工具"算成功" | `FINISH_NEED_BUSINESS` |

---

## 11. 平台字段

`platform.<字段>` 取自本轮登录身份（网关注入的请求头）。

| 字段 | 类型 | 含义 |
|---|---|---|
| `userId` | Long | 用户 ID |
| `memberId` | Long | 成员 ID |
| `name` | String | 用户名 |
| `organId` | Long | 机构 ID |
| `organName` | String | 机构名 |
| `organType` | 枚举 | 机构类型 |
| `deptPath` | String | 部门路径 |

`passportId`、`clientId`、`sessionType`、`adminUser`、`adminCompany` 不开放（配置项 `platform.hidden`）。

规则：

- `platform.<字段>` 中的字段必须在上表中，否则 Skill 加载失败。
- 平台字段名是保留名：产出的 key、共享值的 key 不能与之同名。
- 参数叶子名与平台字段同名、却没有写 `from: platform.*` 时，加载会记录告警。
- 平台注入的参数不出现在审批文案中。

---

## 12. 内置工具

内置工具始终可用，不需要也不应写入 `toolNames`，不受参数契约、审批、业务判定约束。

| 工具 | 参数 | 作用 |
|---|---|---|
| `builtin_node_follow_up` | `question`、`expectedFields?`、`suggestions?` | 向用户追问，节点挂起（`FOLLOW_UP`）。`expectedFields` 中的参数在用户回答后视为来源 `USER` |
| `builtin_node_approval` | `summary`、`toolName` | 请求用户确认被写入闸门拦下的那次写调用，节点挂起（`APPROVAL`）。未先调用写工具或工具名不一致时拒绝（`APPROVAL_NOT_GATED`） |
| `builtin_node_finish` | `output` | 声明完成；通过完成判定（10.2）后节点 `DONE`，写入共享值和实体 |
| `builtin_node_fail` | `reason` | 声明失败，节点 `FAILED` |
| `builtin_node_delegate` | `skillKey`、`objective?` | 委派一个只读技能作为前置节点（见第 15 节） |
| `builtin_output_set` | `key`、`value`、`sourceType`、`sourceRef`、`sourcePath?` | 写一条产出。`sourceType` 不能是 `MODEL`；来源为 `EVIDENCE` 时 `sourceRef` 填 evidenceId、`sourcePath` 填报文路径，值必须与报文中的值相等 |
| `builtin_state_set` | `key`、`value` | 写节点私有便签；不能写保留键 |
| `builtin_evidence_get` | `evidenceId` | 读取证据的原始报文 |
| `builtin_session_info` | — | 返回会话 ID、节点 ID、节点状态 |
| `builtin_env_current_datetime` | — | 当前时间 |
| `builtin_env_generate_uuid` | `length?` | 生成 UUID |
| `builtin_env_format_datetime` | `input`、`inputPattern?`、`outputPattern?` | 日期格式化 |
| `builtin_env_add_datetime` | `base`、`amount`、`unit?` | 日期加减 |
| `builtin_env_diff_datetime` | `from`、`to`、`unit?` | 日期间隔 |
| `builtin_env_get_weekday` | `date` | 星期 |
| `builtin_env_random_int` | `min`、`max` | 随机整数 |
| `builtin_env_hash` | `input`、`algorithm?`、`length?` | 哈希 |

---

## 13. 工具调用处理链

业务工具每次调用依次经过以下处理。before 阶段任一环节拒绝，调用即结束，不登记证据。

| 顺序 | 环节 | 生效的契约属性 | 拒绝时返回 |
|---|---|---|---|
| 1 | 参数规范（ArgPolicy） | 工具 schema；`from`/`const` 参数免除 schema 必填检查 | `ARG_JSON_INVALID`、`ARG_UNRESOLVED_REF`、`ARG_REJECTED` |
| 2 | 参数绑定（ArgBinding） | `from`、`const`、`when` | 不拒绝 |
| 3 | 参数契约（ParamContract） | `required`、`sources`、`enum`、`missing` | `PARAM_PROVENANCE_*` |
| 4 | 重复调用熔断（Fingerprint） | — | 同工具同参数连续成功调用达到上限（默认 3 次）→ `FINGERPRINT_TRIP`，节点失败 |
| 5 | 写入闸门（WriteGate） | `sideEffect`、`confirm`、`idempotencyKey` | `APPROVAL_REQUIRED`、`WRITE_IN_FLIGHT`、`WRITE_PENDING_VERIFY` |
| — | 调用工具 | — | 调用异常转为错误码（见 14.4） |
| 6 | 业务判定（ResponseContract） | `success`、`error` | 业务失败 → `TOOL_BUSINESS_FAILED` 或 `error.code` 的值 |
| 7 | 证据登记（Evidence） | `extracts` | — |
| 8 | 熔断计数（Fingerprint） | — | — |

模型只能看到观测中的文字说明，Runtime 根据错误码决定后续约束（补参义务、审批、去重）。

---

## 14. 写工具：审批、幂等与核实

### 14.1 审批

| 步骤 | 行为 |
|---|---|
| 1 | 模型调用写工具 → 写入闸门返回 `APPROVAL_REQUIRED` |
| 2 | 模型调用 `builtin_node_approval(summary, toolName)` → 待批参数取闸门记下的绑定后参数 → 节点挂起，问题文案 = `summary` + 参数摘要（"标签：值"，敏感参数打码，平台参数不显示） |
| 3 | 用户 `CONFIRM` → 校验参数摘要未变 → 执行该写操作（仍经过完整处理链，跳过审批和熔断）→ 节点继续，模型需自行完成 |
| 3' | 用户 `REJECT` → 节点失败；`CANCEL` → 任务取消 |
| 4 | 审批挂起超过 `state.reconfirm-after`（默认 24 小时）后，确认不再直接执行，需要重新确认 |

免审批：`confirm: false`，并把工具名加入配置 `state.write-confirm-bypass-tools`。两者缺一不可。

### 14.2 幂等

| 配置 | 幂等键 |
|---|---|
| `idempotencyKey: ["title", "spaceId"]` | 按路径字典序取参数值拼接后取 SHA-256；值缺失的路径跳过 |
| 未配置，或所列参数全部缺失 | 工具名 + 全部参数的摘要 |

同一任务内，"工具名 + 幂等键"相同的写操作：

| 已有证据 | 结果 |
|---|---|
| 业务成功 | 不再调用，直接返回上次结果 |
| 进行中 / 结果不确定 | 拒绝调用 |
| 失败 / 业务失败 / 不存在 | 正常调用 |

建议为每个写工具配置能表达"同一业务动作"的 `idempotencyKey`，例如创建文章用 `spaceId + title`。

### 14.3 核实（VERIFY）

写工具结果不确定时（见 14.4），节点再次打开时处理：

- 配置了 `verify` → 自动回查；
- 未配置 → 挂起 `VERIFY` 断点，由用户确认是否已生效。

结论出来之前，同一写操作不能再次调用，节点不能完成。

### 14.4 调用异常的归类

| 异常 | 读工具 | 写工具 |
|---|---|---|
| 超时 | 失败（`TIMEOUT`） | 不确定（`UNKNOWN`） |
| IO 异常 | 失败 | 不确定 |
| MCP RPC 错误、未知错误 | 失败 | 不确定 |
| 服务不可达 | 失败 | 失败 |
| MCP 工具错误、参数错误 | 失败 | 失败 |

---

## 15. 委派

| 项 | 规则 |
|---|---|
| 可委派技能 | 契约 `delegatable: true`，且 `tools` 中没有写工具 |
| 发起 | 节点内模型调用 `builtin_node_delegate(skillKey, objective)` |
| 深度 | 不超过 `state.delegate-max-depth`（默认 2） |
| 重复 | 同一发起节点下，相同技能、相同目标的前置节点已失败、取消或跳过时，拒绝再次委派 |
| 执行 | 前置节点先执行，发起节点等待；前置节点结束后发起节点继续 |
| 结果 | 前置节点成功 → 发起节点可通过 `evidence.*` 使用其证据，`TOOL succeeded` 条件也认可其证据；前置节点失败 → 发起节点收到一条委派结果说明，由它决定后续处理，任务不会因此失败 |

---

## 16. 装载校验

Skill 每轮加载时校验契约。任一规则不满足，该 Skill 被丢弃（只记录错误日志）；数字员工没有任何可用 Skill 时，规划失败。

| # | 规则 |
|---|---|
| 1 | `skill_contract` 非空，且是合法 JSON |
| 2 | 所有 `args.*.from` 的前缀合法 |
| 3 | `doneWhen` 中每个条件都是 10.1 中的合法形式 |
| 4 | `delegatable=true` 时，`tools` 中没有 `WRITE` 工具 |
| 5 | `tools` 的 key 非空 |
| 6 | `WRITE` 工具配置了 `success` |
| 7 | `confirm: true` 只出现在 `WRITE` 工具上 |
| 8 | `verify.tool` 非空、在 `contract.tools` 中、且为 `NONE` |
| 9 | 同一参数不能同时有 `from` 和 `const` |
| 10 | `platform.<字段>` 的字段是开放的平台字段 |
| 11 | 每个 `extracts[].path` 非空 |
| 12 | `type: LIST` 的产出配置了非空 `fields` |
| 13 | 枚举属性的取值合法（区分大小写） |

以下内容**不在装载时校验**，由编写者保证：

- `target`、`missing.tool`、`verify.tool` 是否在 `toolNames` 中；
- `doneWhen` 引用的工具、产出是否已声明；
- `idempotencyKey`、`verify.args` 引用的参数是否存在；
- `success.op` 是否配置；
- 必填参数是否有可满足的来源；
- `inputs.provider` 是否存在（规划时检查，不存在则规划失败）。

---

## 17. 错误码

以下错误码出现在工具观测中，供模型和日志使用。

| 错误码 | 名称 | 含义 |
|---|---|---|
| `agent_runtime.40003` | `MCP_TOOL_ERROR` | MCP 工具返回错误 |
| `agent_runtime.40004` | `TOOL_BUSINESS_FAILED` | 业务失败（未配置 `error.code` 时） |
| `agent_runtime.40014` | `MCP_INVALID_PARAMS` | MCP 参数错误 |
| `agent_runtime.40015` | `MCP_RPC_ERROR` | MCP RPC 错误 |
| `agent_runtime.40016` | `TOOL_TIMEOUT` | 工具超时 |
| `agent_runtime.40017` | `TOOL_UNREACHABLE` | 工具不可达 |
| `agent_runtime.40018` | `TOOL_IO_ERROR` | 工具 IO 异常 |
| `agent_runtime.40029` | `TOOL_ERROR` | 工具未知错误 |
| `agent_runtime.40020` | `ARG_JSON_INVALID` | 参数不是合法 JSON |
| `agent_runtime.40021` | `ARG_UNRESOLVED_REF` | 参数中残留未解析的引用 |
| `agent_runtime.40032` | `ARG_REJECTED` | 参数不是对象，或缺少 schema 必填项 |
| `agent_runtime.40022` | `PARAM_PROVENANCE_FAIL` | 必填参数缺失且配置为失败 |
| `agent_runtime.40023` | `PARAM_PROVENANCE_CALL_TOOL` | 须先调用补数工具 |
| `agent_runtime.40024` | `PARAM_PROVENANCE_ASK_USER` | 须向用户追问 |
| `agent_runtime.40025` | `PARAM_PROVENANCE_REJECTED` | 参数来源不可信 |
| `agent_runtime.40026` | `FINGERPRINT_TRIP` | 重复调用熔断 |
| `agent_runtime.40027` | `CATALOG_DENIED` | 工具不在本节点可调用范围 |
| `agent_runtime.40028` | `STATE_KEY_RESERVED` | 写入了保留便签键 |
| `agent_runtime.40030` | `WRITE_PENDING_VERIFY` | 写操作结果待确认 |
| `agent_runtime.40031` | `WRITE_IN_FLIGHT` | 写操作进行中 |
| `agent_runtime.42221` | `APPROVAL_REQUIRED` | 写操作须先经用户确认 |
| `agent_runtime.42206` | `WRITE_CONTRACT_INCOMPLETE` | 审批的工具不是本契约的写工具 |
| `agent_runtime.42223` | `SOURCE_REJECT` | 产出来源校验失败 |
| `agent_runtime.42236` | `PLATFORM_FIELD_FORBIDDEN` | 使用了平台保留字段名 |
| `agent_runtime.42243` | `OUTPUT_PATH_INVALID` | 产出键为空 |
| `agent_runtime.42244` | `OUTPUT_WRITE_FORBIDDEN` | 产出键未声明 |
| `agent_runtime.42235` | `EVIDENCE_PATH_INVALID` | 证据 ID 为空 |
| `agent_runtime.40411` | `SKILL_NOT_FOUND` | 委派的技能不存在 |
| `agent_runtime.42237` | `DELEGATE_FORBIDDEN` | 禁止委派 |
| `agent_runtime.42239` | `DELEGATE_REPEATED` | 重复委派 |
| `agent_runtime.42212` | `PROVIDER_UNAVAILABLE` | `inputs.provider` 不可用（规划失败） |

---

## 18. 枚举汇总

| 枚举 | 取值 |
|---|---|
| `mode` | `REACT`、`PLAN` |
| `sideEffect` | `NONE`、`WRITE` |
| `args.*.when` | `ALWAYS`、`UNIQUE` |
| `args.*.sources`、`outputs.*.sources` | `USER`、`EVIDENCE`、`NODE_OUTPUT`、`SHARED`、`SESSION`、`PRIOR_TASK`、`MODEL`、`CONTRACT`、`PLATFORM` |
| `args.*.missing.action` | `ASK_USER`、`CALL_TOOL`、`FAIL` |
| `success.op` | `EQ`、`NE`、`IN`、`NOT_IN`、`TRUTHY`、`EXISTS` |
| `outputs.*.type` | `VALUE`、`LIST` |
| `outputs.*.share` | `TASK`、`SESSION` |
| `from` 前缀 | `inputs.`、`outputs.`、`shared.`、`session.`、`prior.`、`platform.`、`evidence.`（`call.` 仅限 `verify.args`） |

所有枚举值区分大小写。

---

## 19. 解析规则与未生效属性

**解析规则**

| 规则 | 说明 |
|---|---|
| 未知属性 | 忽略，不报错 |
| 注释 | 允许 `//`、`/* */` |
| 空字符串 | 视为未配置 |
| 枚举 | 区分大小写；非法值导致 Skill 加载失败 |
| 常量键名 | 参数常量是 `args.*.const`，产出常量是 `outputs.*.constant` |

**当前未生效的属性**（可以配置，但不影响行为）

| 属性 | 状态 |
|---|---|
| `args.*.fresh` | 不读取 |
| `outputs.*.sensitive` | 不读取 |
| `outputs.*.minItems`、`outputs.*.hint`、`outputs.*.fields` | 只投影进提示词，不参与校验（`LIST` 的 `fields` 非空校验除外） |
| `inputs.*.required` | 只在提示词中标记缺失，不阻止执行 |
