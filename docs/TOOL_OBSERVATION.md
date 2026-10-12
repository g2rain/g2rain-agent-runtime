# 工具观测与节点推进规范

本文档定义工具调用结果（`Observation`）的结论类型、各产生方如何归类、节点内循环如何按结论计数，以及返回给模型的提示必须满足的约定。新增工具、拦截器或内置工具时按本文执行。

---

## 目录

1. [背景](#1-背景)
2. [设计原则](#2-设计原则)
3. [结论类型](#3-结论类型)
4. [归类规则](#4-归类规则)
5. [现有产生方归类](#5-现有产生方归类)
6. [节点内循环的计数](#6-节点内循环的计数)
7. [提示约定](#7-提示约定)
8. [代码改动](#8-代码改动)
9. [验收](#9-验收)

---

## 1. 背景

联调中，写操作申请确认的流程被判为"连续无进展"而失败：写入拦截器拦下写调用、要求先申请确认，这本是流程中预期的一步，但观测只有 `ok` 一个布尔值，节点内循环无法区分"工具执行失败""模型调用违规被拒""调用正当但须先完成前置步骤"，一律计为无进展。

根因不是某个场景处理不当，而是观测缺少"结论是什么性质"的表达。本规范让观测在产生处自带结论类型，循环只按类型统一处理，不认识任何具体场景。

之后的联调又出现一类问题：查询成功后，模型反复调用 `builtin_output_set` 写一个非必填产出，5 次都返回"来源校验失败：web_site_list"，触发连续无进展而失败。拒绝消息只说失败、不说原因和下一步，模型只能换着参数重试；同时节点提示在"有证据、无产出"时一律提示"写 outputs"，不看产出是否必填，把模型引向了这一步。两者都与具体场景无关：前者是观测没有强制"原因 + 下一步"，后者是 runtime 的提示越过契约推断进度。

---

## 2. 设计原则

1. **大模型决定，runtime 守规则。** 调用哪个工具、何时申请确认、何时追问或收口，都由模型决定；runtime 只拦下违规调用，并告诉模型正确的下一步，不替模型调用工具。
2. **结论由产生方判定。** 只有产生观测的一方（工具调用、拦截器、内置工具）知道这次结果的性质，归类在产生处完成。
3. **循环只认结论类型。** 节点内循环按结论类型统一计数，不出现任何具体错误码或场景判断。新增场景只需在产生处选对类型，循环不改。
4. **非成功必须给出原因和下一步。** 非成功观测由工厂方法强制传入原因与下一步，消息统一为"原因。下一步：…"；错误码用 `RuntimeErrorCode` 枚举，不用字符串。
5. **提示只陈述契约事实。** runtime 给模型的阶段提示只说明契约可判定的义务（必填产出是否写齐），不推断任务进度。

---

## 3. 结论类型

`domain.tool.Outcome`：

| 结论 | 含义 | 推进 | 停滞 |
|---|---|---|---|
| `SUCCESS` | 工具真正执行并成功 | 是（清零无进展） | 否 |
| `FAILED` | 工具真正执行了，但失败（超时、不可达、业务失败等） | 否 | 是（记一次无进展） |
| `REJECTED` | 调用违反规则，执行前被拒（参数不合规、来源不符、工具不可见、熔断、内置工具拒绝等） | 否 | 是（记一次无进展） |
| `DEFERRED` | 调用本身正当，但须先完成前置步骤（申请确认、等待写结果核实等） | 否 | 否（不计也不清零，由总轮次上限兜底） |

推进与停滞是结论类型自身的属性（`Outcome.advances()`、`Outcome.stalls()`），不在使用处另行判断。

---

## 4. 归类规则

按"模型按规则行事能否避免这次结果"判断：

| 情况 | 归类 |
|---|---|
| 工具实际被调用，返回成功（含复用已有成功写证据） | `SUCCESS` |
| 工具实际被调用，但调用层或业务层失败 | `FAILED` |
| 模型只要遵守契约、工具清单与内置工具规则就能避免 | `REJECTED` |
| 由协议或运行态决定必须先走另一步，模型无法事先避免 | `DEFERRED` |

拿不准时归 `REJECTED`：它会计入无进展，最坏结果是提前止损；误归 `DEFERRED` 会让违规调用一直耗到轮次上限。

---

## 5. 现有产生方归类

| 产生方 | 情况 | 错误码 | 归类 |
|---|---|---|---|
| `DefaultToolInvoker` | 工具返回 | — | `SUCCESS` |
| `DefaultToolInvoker` | 超时、不可达、IO、MCP RPC / 参数错误、兜底异常 | `TOOL_TIMEOUT` / `TOOL_UNREACHABLE` / `TOOL_IO_ERROR` / `MCP_RPC_ERROR` / `MCP_INVALID_PARAMS` / `TOOL_ERROR` | `FAILED` |
| `DefaultToolInvoker` | MCP 工具抛出业务异常 | `MCP_TOOL_ERROR` | `FAILED` |
| `DefaultToolInvoker` | 内置工具抛出 `ToolRejectedException`（如申请确认未经拦截、产出来源不符、委派越界） | 异常自带错误码 | `REJECTED` |
| `DefaultToolInvoker` | 内置工具抛出其它业务异常（运行态缺失、契约配置错误等） | `TOOL_ERROR` | `FAILED` |
| `ResponseContractInterceptor` | 按契约判定业务失败；下游错误码写进原因 | `TOOL_BUSINESS_FAILED` | `FAILED` |
| `ArgPolicyInterceptor` | 入参 JSON 非法、不是对象、被策略拒绝 | `ARG_JSON_INVALID` / `ARG_REJECTED` | `REJECTED` |
| `ParamContractInterceptor` | 参数来源不符、须先查询、须先追问、被拒 | `PARAM_PROVENANCE_*` | `REJECTED` |
| `FingerprintInterceptor` | 相同参数重复调用熔断 | `FINGERPRINT_TRIP` | `REJECTED` |
| `WriteGateInterceptor` | 写操作须先申请确认 | `APPROVAL_REQUIRED` | `DEFERRED` |
| `WriteGateInterceptor` | 写结果待核实、写操作进行中 | `WRITE_PENDING_VERIFY` / `WRITE_IN_FLIGHT` | `DEFERRED` |
| `WriteGateInterceptor` | 复用已有成功写证据 | — | `SUCCESS` |
| `DefaultReActLoop` | 工具不在本节点可见表 | `CATALOG_DENIED` | `REJECTED` |

说明：

- 内置工具抛出的异常可能被工具框架包装，调用层沿异常链查找业务异常。只有 `ToolRejectedException` 归为拒绝：它必须带下一步，调用层据此生成 `REJECTED` 观测。
- 证据结算（`EvidenceInterceptor`、`ToolErrors`）只处理实际执行过的调用，即 `SUCCESS` 与 `FAILED`；执行前被拦下的调用不进入结算。

---

## 6. 节点内循环的计数

每次工具调用后：

1. 写入工具消息（观测消息作为 `role=tool` 内容）。
2. 节点已由控流工具退出 → 结束循环。
3. 完成被拒 → 记一次无进展，进入下一轮（拒绝原因已在 finish 的返回中）。
4. 结论 `stalls()` → 记一次无进展，跳过同波其余调用，进入下一轮；超过上限判节点失败。
5. 结论非 `SUCCESS` → 跳过同波其余调用，进入下一轮（`DEFERRED` 走这里，不计无进展）。
6. 结论 `advances()` → 清零无进展。

模型未调用工具、只输出文字的轮次，仍按循环层规则记无进展。

---

## 7. 提示约定

### 7.1 消息结构

非成功观测的消息统一为：

```
<原因>。下一步：<下一步>
```

例：`缺少必填参数 [buyer]。下一步：补齐后再调用；无法补齐时调用 builtin_node_follow_up 询问用户，或调用 builtin_node_fail 说明原因`。

结构由 `Observation` 的工厂方法强制，不靠各处自觉：

| 工厂方法 | 参数 |
|---|---|
| `success(message, data)` | 成功摘要、业务载荷 |
| `failed(errorCode, reason, next[, data])` | 错误码、原因、下一步、响应体 |
| `rejected(errorCode, reason, next)` | 错误码、原因、下一步 |
| `deferred(errorCode, reason, next)` | 错误码、原因、须先完成的步骤 |

- 非成功观测缺错误码或下一步时直接抛出异常，不允许构造。
- 错误码类型为 `RuntimeErrorCode`。下游接口的错误码不进入 `errorCode`，写进原因（`业务失败 [system.50001]：…`），运行时错误码统一为 `TOOL_BUSINESS_FAILED`。持久化与证据仍按 `code()` 存字符串。

### 7.2 下一步的来源

| 产生方 | 下一步来自 |
|---|---|
| 拦截器、`DefaultReActLoop` | `ModelHint.NEXT_*`，原因用对应的 `ModelHint` 原因文案 |
| 调用层执行失败 | `ToolErrors` 每一项配置的下一步（超时、重试、修正参数、业务失败） |
| 内置工具拒绝 | `ToolRejectedException` 构造时传入；原因为错误码文案模板 |
| 业务失败 | `ModelHint.NEXT_BUSINESS` |

`ModelHint` 的原因文案只写发生了什么，不写处理建议；处理建议一律放在 `NEXT_*`。

### 7.3 具体约定

- 写操作须先确认：原因"该写操作须经用户确认"，下一步给出 `builtin_node_approval` 及被拦下的写工具名。
- 申请确认的工具名与被拦下的不一致：下一步给出实际被拦下的写工具名（`APPROVAL_TOOL_MISMATCH`）；没有被拦下记录或申请的不是写工具：下一步为先直接调用写工具（`APPROVAL_NOT_GATED`）。
- 已批准的写操作执行失败：`WriteGateInterceptor.after` 把下一步换成"用 builtin_node_follow_up 向用户说明失败原因并询问如何处理；用户不再继续时 builtin_node_fail；不要自行改用其它写操作补救"。批准只针对那一次调用，失败后是否补救、如何补救由用户决定，任务不中断。
- `builtin_output_set` 被拒：原因写明哪一项不符（键未声明、来源类型不存在或不被接受、缺少 sourceRef、值与来源不一致），下一步写明可用的键、来源类型或取值要求；产出非必填时另提示"也可以不写，直接调用 builtin_node_finish"。
- 节点提示词（`ReactPrompt`）写明通用规则：工具未成功时按返回中"下一步："之后的内容执行，不换参数反复重试；写工具先直接调用，被拦下后再按提示申请确认。

### 7.4 阶段提示

`PhaseHint` 只按契约给出必填产出的事实：契约没有必填产出时不提示；有必填产出时列出尚未写入的键，全部写入后提示已写齐。不再根据"有无证据、有无产出"推断"查业务 / 写 outputs / 可收尾"。

---

## 8. 代码改动

| 类 | 改动 |
|---|---|
| `domain.tool.Outcome` | 新增：四种结论及 `advances()`、`stalls()` |
| `domain.tool.Observation` | `ok` 字段改为 `outcome`；工厂方法为 `success`、`failed`、`rejected`、`deferred`；删除 `failure` 与 `ok()` |
| `engine.tools.DefaultToolInvoker` | 按第 5 节归类；沿异常链识别内置工具业务异常；轨迹输出 `outcome`，`FAILED` 时 WARN |
| `engine.tools.ToolErrors` | 按 `outcome` 判断调用层失败与业务失败 |
| `engine.tools.guard.*` | 各拦截器按第 5 节归类 |
| `engine.flow.react.DefaultReActLoop` | 按第 6 节计数；不可见工具归 `REJECTED` |
| `engine.flow.graph.DefaultNodeRunner` | 回查结果按 `outcome` 判断 |
| `engine.tools.builtin.ControlBuiltinTools` | 申请确认工具名不一致时返回实际被拦下的工具名 |
| `enums.ModelHint` | `WRITE_NEED_APPROVAL` 写明下一步与工具名 |
| `enums.RuntimeErrorCode` | 新增 `APPROVAL_TOOL_MISMATCH`（42246） |
| `support.prompt.ReactPrompt` | 补充第 7 节的通用规则 |

第二轮（下一步强制、错误码枚举、阶段提示）：

| 类 | 改动 |
|---|---|
| `domain.tool.Observation` | `errorCode` 改为 `RuntimeErrorCode`；非成功工厂方法改为 `(errorCode, reason, next)`，统一拼成"原因。下一步：…"；非成功缺错误码或下一步时拒绝构造 |
| `engine.tools.ToolRejectedException` | 新增：内置工具拒绝调用时抛出，必带下一步 |
| `engine.tools.ToolErrors` | 按枚举查找；每项配置下一步，`observe(reason)` 生成失败观测 |
| `engine.tools.DefaultToolInvoker` | 只把 `ToolRejectedException` 归为拒绝；其它业务异常按执行失败 |
| `engine.tools.guard.*`、`DefaultReActLoop` | 原因与下一步分开传入 |
| `engine.tools.guard.ResponseContractInterceptor` | 错误码统一 `TOOL_BUSINESS_FAILED`，下游错误码写进原因 |
| `engine.tools.builtin.NodeBuiltinTools`、`ControlBuiltinTools`、`engine.state.OutputContractValidator` | 拒绝改抛 `ToolRejectedException`，按具体原因给出下一步 |
| `engine.flow.react.PhaseHint`、`DefaultPhaseHint` | 改为按契约必填产出提示 |
| `enums.ModelHint` | 原因文案去掉处理建议；新增 `NEXT_*`、`CATALOG_DENIED`、`BUSINESS_FAILED`、`BUSINESS_FAILED_CODE` |
| `enums.RuntimeErrorCode` | `APPROVAL_NOT_GATED`、`APPROVAL_TOOL_MISMATCH` 文案只写原因 |
| `domain.tool.Observation` | 原因与下一步分字段保存（`text`、`next`），`message()` 拼接；新增 `withNext` |
| `engine.tools.guard.WriteGateInterceptor` | 新增 `after`：已批准写操作执行失败时下一步改为追问用户（`ModelHint.NEXT_APPROVED_FAILED`） |

---

## 9. 验收

| # | 场景 | 期望 |
|---|---|---|
| 1 | 用户要求建站（写操作），模型直接调用写工具 | TOOL `invoked outcome=DEFERRED`，不出现 REACT `no_tool_calls`；下一轮模型调用 `builtin_node_approval`，节点挂起为 APPROVAL |
| 2 | 模型先调用 `builtin_node_approval` | TOOL `outcome=REJECTED`，错误码 `APPROVAL_NOT_GATED`，提示先调用写工具；计一次无进展 |
| 3 | 申请确认时工具名填错 | `outcome=REJECTED`，错误码 `APPROVAL_TOOL_MISMATCH`，提示给出实际被拦下的工具名 |
| 4 | 用户回答追问后，模型原样再问同一问题 | `outcome=REJECTED`，错误码 `FOLLOW_UP_ANSWERED`，观察带上用户的回答，下一步为按回答继续 |
| 5 | 模型用追问口头确认写操作，或复述用户原话 | `outcome=REJECTED`，错误码 `FOLLOW_UP_UNGROUNDED`，下一步为直接调用写工具、确认由审批完成 |
| 6 | 参数来源不符被拦 | `outcome=REJECTED`，计无进展 |
| 7 | MCP 工具超时 | `outcome=FAILED`，WARN，计无进展 |
| 8 | 用户确认后执行写操作成功 | `outcome=SUCCESS`，节点继续 |
| 9 | 查询网站与空间（非必填产出） | 查询后直接 `builtin_node_finish`；节点提示中不出现"写 outputs" |
| 10 | `builtin_output_set` 被拒 | 消息形如"来源校验失败：…。下一步：…"，非必填产出带"也可以不写"；模型据此结束或修正，不反复重试 |
| 11 | 确认后写操作业务失败 | 消息"下一步："为向用户说明并询问；模型调用 `builtin_node_follow_up`，不自行发起其它写操作 |
| 12 | 下游业务失败 | `errorCode=TOOL_BUSINESS_FAILED`，消息含下游错误码与"下一步：" |
