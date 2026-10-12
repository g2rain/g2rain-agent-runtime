# 链路轨迹日志规范

本文档定义 Runtime 的链路轨迹日志：一轮对话经过哪些环节、每个环节做了什么决定、每次大模型调用的结果。所有轨迹从 `support.trace` 一个出口输出，由一份配置控制。新增环节或埋点时按本文的约定执行。

---

## 目录

1. [目标与非目标](#1-目标与非目标)
2. [设计总览](#2-设计总览)
3. [关键设计决策](#3-关键设计决策)
4. [使用规范](#4-使用规范)
5. [配置](#5-配置)
6. [输出格式](#6-输出格式)
7. [大模型调用日志](#7-大模型调用日志)
8. [埋点清单](#8-埋点清单)
9. [现有日志迁移](#9-现有日志迁移)
10. [代码改动](#10-代码改动)
11. [验收与测试](#11-验收与测试)
12. [联调排障指引](#12-联调排障指引)

---

## 1. 目标与非目标

### 1.1 目标

| # | 目标 |
|---|---|
| G1 | 只用 traceId 一个关键字，就能还原一轮对话的完整链路：入口 → 决策 → 任务 → 装载 → 计划 → 节点 → 工具 / 模型 → 收口 |
| G2 | 一轮内的每一行日志（含普通日志）自动带会话、任务、节点 ID，不靠逐条传参 |
| G3 | 每次大模型调用都有一行摘要；异常调用自动留下完整请求与响应 |
| G4 | 生产环境可以只对单个请求打开完整内容，不改全局配置 |
| G5 | 只有一个出口、一个 logger、一份配置；业务代码打点只写一行 |
| G6 | 默认不输出用户原文、模型正文、工具参数原值 |

### 1.2 非目标

- 不落库：轨迹只写日志；工具调用审计由 `tool_call_record` 表承担。
- 不做按事件、按层的细粒度开关，只按环节开关。
- 不替代组件内部的错误与诊断日志。

---

## 2. 设计总览

```
support.trace
 ├─ TraceStage   链路环节枚举，每个环节一个开关
 ├─ Trace        唯一入口：Trace.at(stage, event) 开始一条事件
 ├─ TraceEvent   链式追加 key=value，按级别 debug() / info() / warn() / error() 输出一行
 └─ TraceScope   会话 / 任务 / 节点 ID 写入 MDC，作用域结束自动恢复

engine.model
 ├─ ModelUse            模型调用用途
 └─ DefaultModelGateway 每次调用输出 MODEL 摘要；异常时输出完整内容

config
 ├─ RuntimeProperties.Trace  轨迹配置
 └─ RuntimeConfig            启动时把配置绑定到 Trace
```

- 所有轨迹使用同一个 logger：`g2rain.agent.trace`，由 `Trace` 唯一持有；级别按 4.2 的规范选择。
- MDC 跨虚拟线程的传递由框架 `Contexts` 完成：父线程捕获整份 MDC，子线程恢复，结束时还原。`TraceScope` 写入的 ID 随之传到本轮的所有子线程。

---

## 3. 关键设计决策

### D1 一个出口、一个 logger

- **采用**：所有轨迹经 `Trace` 输出，logger 固定为 `g2rain.agent.trace`。
- **备选**：各类用自己的 logger，靠前缀约定。
- **理由**：出口唯一才能保证格式、开关、脱敏规则一致；logger 固定后，logback 可以单独调级别或单独输出，不用改代码。

### D2 静态入口

- **采用**：`Trace` 为静态方法，启动时由 `RuntimeConfig` 绑定配置；未绑定时（如单元测试）使用默认配置。
- **备选**：注入 Bean。
- **理由**：打点遍布各环节，静态入口不需要给每个类增加依赖。配置以对象引用绑定，配置中心刷新后实时生效。

### D3 定位信息放 MDC

- **采用**：`TraceScope` 把 `conversationId`、`taskId`、`nodeId` 写入 MDC，logback 输出格式统一带上。
- **备选**：每条事件手动传会话、节点等定位对象。
- **理由**：一次写入，本轮所有日志（包括普通日志）自动带上；不会因为漏传而丢失定位信息。

### D4 作用域只在两个编排边界

- **采用**：`TraceScope.turn` 只在会话服务 `ConversationServiceImpl.send`（覆盖加锁、编排与落盘），`TraceScope.node` 只在 `DefaultNodeRunner.run`，各包一行方法调用；任务 ID 用 `TraceScope.bindTask` 一行写入，由 turn 作用域统一清理。
- **备选**：Lambda 包装，或注解加切面。
- **理由**：MDC 绑定在线程上，必须保证清理，try-with-resources 最直接；Lambda 不便处理受检异常和返回值，切面会把写入和清理变成隐式，且对私有方法和类内调用不生效。

### D5 只按环节开关

- **采用**：配置列出开启的环节；模型内容另设三档。
- **备选**：按事件、按层细分开关。
- **理由**：环节粒度已足够定位问题；更细的开关会互相重叠，难以维护。

### D6 模型调用在网关统一记录

- **采用**：`ModelGateway.chat(ModelUse, request)` 显式传入用途，`DefaultModelGateway` 统一输出摘要与异常内容。
- **备选**：各调用方自行记录，或通过 MDC 隐式传递用途。
- **理由**：网关是所有模型调用的必经点，记录不会遗漏；用途显式传参，编译期就能发现漏传。

### D7 模型内容默认只在异常时输出

- **采用**：`payload` 默认 `ABNORMAL`；请求带 `DEBUG_PRINT_LEVEL=TRUE` 时，该请求按 `ALL` 处理。
- **备选**：默认 `NONE`。
- **理由**：异常时保留原文是排查的关键证据，日志量可控；单请求开关让生产排查不必改全局配置。

### D8 一行 key=value

- **采用**：每条事件输出一行 `[Trace][环节] 事件 key=value ...`。
- **备选**：多行快照或 JSON。
- **理由**：方便 grep，也方便日志平台按字段解析；多行内容只出现在模型完整内容中，并且单独成行。

### D9 与普通日志并存，不单独输出文件

- **采用**：轨迹与普通日志写同一个文件，靠 `[Trace]` 前缀和 traceId 过滤。
- **备选**：单独一个文件。
- **理由**：排查时轨迹和错误堆栈在同一处按时间排列；需要时可在 logback 中按 logger 单独输出，不影响代码。

---

## 4. 使用规范

### 4.1 写进轨迹，还是写普通日志

| 写进 `Trace` | 写普通 `log` |
|---|---|
| 链路做出的决定：受理还是拒绝、选了哪种模式、绑定了谁、建了什么计划、节点结果、工具结果 | 组件内部的异常、重试、降级细节、配置错误 |
| 每轮固定会经过的里程碑 | 只在特定条件下出现的诊断信息 |

一个环节的同一个决定只输出一次：在做出决定的地方输出，不在调用方重复输出。

### 4.2 打点写法

```java
Trace.at(TraceStage.DECISION, "settled")
    .kv("scene", scene)
    .kv("proposed", proposed)
    .kv("settled", mode)
    .info();
```

按结论的性质选择级别：

| 级别 | 用于 | 例子 |
|---|---|---|
| `debug()` | 只在排查时需要的细节；平时不输出，请求带调试头时可见 | 附带更多字段的明细 |
| `info()` | 链路的正常里程碑 | 入口结论、选人、出图、节点开始与结束 |
| `warn()` | 异常结论，本轮仍可继续或正常收口 | 模型返回空、工具执行失败、没有合适的数字员工 |
| `error()` | 导致本轮失败的结论 | 指定员工不可用、计划失败、节点停滞或轮次用尽、本轮以失败收口 |

- 轨迹不带异常堆栈；异常与堆栈由出错组件用自己的 `log.error(..., ex)` 输出，两者同在一个 traceId 下按时间对应。
- 环节关闭时 `Trace.at` 返回空操作，后续调用没有开销；构造代价高的值先用 `Trace.enabled(stage)` 判断。`enabled` 只看总开关与环节开关，日志级别由输出时的级别判断。

### 4.3 命名

- **事件名**：小写英文，多个单词用下划线，表达"发生了什么"，如 `settled`、`agent_bound`、`node_end`。同一环节内不重名。
- **字段名**：小驼峰，与 Java 字段名一致，如 `nodeId`、`skillKey`、`durationMs`。时长统一为 `durationMs`，数量统一以 `Count` 结尾。
- **字段值**：枚举输出 `name()`；集合输出 `[a,b]`；含空格或 `=` 的值加双引号；单个值超过 200 字符截断并以 `…` 结尾；`null` 输出 `-`。
- **取值固定的字段用类型表达，不写字符串字面量**：优先复用已有类型（如 `TurnMode`、`TurnStatus`、`GraphStep` 的实现类型）；没有现成类型时，业务上有意义的概念放到 domain 包（如 `EntryOutcome`），只在本类内部用于标记的定义为私有嵌套枚举（如规划的 `SkipReason`、`FailReason`、`PlanSource`）。能由已有字段表达的不新增字段（如工具结果用 `outcome`、`errorCode`、`blockedBy`）。
- **事件名保留字面量**：作用等同日志消息文本，每个只在一处出现。
- **不写三目嵌套**：多分支判断用 if 逐个返回，或交给对象自身的方法（如 `EntryResult.outcome()`）。

### 4.4 作用域

- `TraceScope.turn(conversationId)`：只在 `ConversationServiceImpl.send`，覆盖加锁、编排与 flush / persist。开启时写入会话 ID，并清掉残留的任务、节点 ID；结束时三者都恢复到开启前的值。
- `TraceScope.node(nodeId)`：只在 `DefaultNodeRunner.run`。结束时恢复为开启前的节点 ID。
- `TraceScope.bindTask(taskId)` 的调用点：状态回灌之后（`ConversationServiceImpl`，写入当前活动任务）与新建任务（`DefaultTaskLifecycle.openTask`）。
- `TraceScope.bindTask(taskId)`：本轮开始时写入当前活动任务；新建或替换任务后重新写入。
- 其它地方不得使用 `TraceScope`。

### 4.5 禁止输出

- 用户原文、模型正文、工具结果原文。
- 工具参数原值：参数只输出 `ArgsBrief` 脱敏摘要。
- 例外：MODEL 环节在 `payload=ABNORMAL` 命中异常，或 `payload=ALL` 时，输出模型的完整请求与响应。

---

## 5. 配置

```yaml
g2rain:
  agent:
    runtime:
      trace:
        enabled: true                 # 总开关
        stages: [ENTRY, DECISION, TASK, HYDRATE, PLAN, GRAPH, NODE, REACT, TOOL, MODEL, FINALIZE, STATE]
        model:
          payload: ABNORMAL           # NONE / ABNORMAL / ALL
          max-chars: 4000             # 完整内容中请求、响应各自的截断上限
```

- `stages` 不配置时默认全部开启。
- `state.state-size-log-enabled` 删除，由 `STATE` 环节开关代替。
- 配置中心刷新后实时生效。

---

## 6. 输出格式

输出格式默认由 tracing starter 提供（`logging.pattern.console` / `logging.pattern.file`，最低优先级）。runtime 在 `application.yml` 中覆盖这两项，在 traceId 之后增加一段定位信息；`logback-spring.xml` 不再定义格式：

```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-}] [%X{spanId:-}] [%X{requestId:-}] [conv=%X{conversationId:--} task=%X{taskId:--} node=%X{nodeId:--}] %logger{36} - %msg%n
```

示例（省略时间和线程）：

```
INFO  [3f9a...] [...] [...] [conv=c-81 task=- node=-]       g2rain.agent.trace - [Trace][ENTRY] result outcome=ACCEPT action=- explicitAgentId=-
INFO  [3f9a...] [...] [...] [conv=c-81 task=- node=-]       g2rain.agent.trace - [Trace][MODEL] call use=INTENT model=Qwen3.6-27B-bw durationMs=812 promptTokens=1830 completionTokens=46 finishReason=stop contentChars=128 toolCallCount=0
INFO  [3f9a...] [...] [...] [conv=c-81 task=- node=-]       g2rain.agent.trace - [Trace][DECISION] settled scene=FRESH proposed=START_FRESH settled=START_FRESH locked=false writePending=false
INFO  [3f9a...] [...] [...] [conv=c-81 task=t-12 node=-]    g2rain.agent.trace - [Trace][TASK] opened taskId=t-12 mode=START_FRESH agentId=1001
INFO  [3f9a...] [...] [...] [conv=c-81 task=t-12 node=n-1]  g2rain.agent.trace - [Trace][TOOL] invoked tool=create_invoice outcome=SUCCESS durationMs=233 args={"buyer":"***","amount":100}
INFO  [3f9a...] [...] [...] [conv=c-81 task=t-12 node=-]    g2rain.agent.trace - [Trace][FINALIZE] turn_done status=DONE mode=START_FRESH taskStatus=SUCCEEDED replyChars=42 durationMs=5321
```

---

## 7. 大模型调用日志

### 7.1 用途

| `ModelUse` | 调用方 |
|---|---|
| `INTENT` | `DefaultIntentClassifier` |
| `SKILL_CANDIDATE` | `DefaultPlanner`（技能候选） |
| `PLAN` | `DefaultPlanner`（出图） |
| `REACT` | `DefaultReActLoop` |
| `GOAL_GATE` | `DefaultGoalGate` |

### 7.2 摘要（每次调用一行）

`[Trace][MODEL] call`，字段：`use`、`model`、`durationMs`、`promptTokens`、`completionTokens`、`finishReason`、`contentChars`、`toolCallCount`。调用抛异常时输出 `call_failed`，字段：`use`、`durationMs`、`error`（异常类型与消息），级别 WARN。

### 7.3 异常判定

| 判定 | 条件 |
|---|---|
| `EMPTY` | 没有 choices，或正文为空且没有工具调用 |
| `LENGTH` | `finishReason=length` |
| `ERROR` | 调用抛出异常 |

命中时，摘要行追加 `abnormal=EMPTY|LENGTH|ERROR` 并以 WARN 输出。

### 7.4 完整内容

| `payload` | 何时输出 |
|---|---|
| `NONE` | 从不 |
| `ABNORMAL` | 命中异常判定时 |
| `ALL` | 每次调用 |

请求带 `DEBUG_PRINT_LEVEL=TRUE`（框架按请求写入 MDC）时，该请求按 `ALL` 处理。完整内容紧跟在摘要行之后，输出两行：

```
[Trace][MODEL] payload use=INTENT part=request body=<单行 JSON，按 max-chars 截断>
[Trace][MODEL] payload use=INTENT part=response body=<单行 JSON，按 max-chars 截断>
```

---

## 8. 埋点清单

| 环节 | 事件 | 位置 | 字段 |
|---|---|---|---|
| ENTRY | `result` | `DefaultTurnPipeline`（入口结论之后） | `outcome`（`EntryOutcome`：ACCEPT / CONTINUE / SWITCH / REJECT）、`action`、`explicitAgentId`、`reason`（拒绝文案） |
| ENTRY | `approval_expired` | `DefaultTurnEntryGuard` | `taskId`；挂起的审批已超时，WARN |
| DECISION | `settled` | `DefaultTurnDispatcher.decideMode` | 模型路径：`source=MODEL`、`scene`、`proposed`、`settled`、`locked`、`writePending`；硬协议：`source=PROTOCOL`、`settled` |
| DECISION | `agent_bound` | `DefaultAgentResolver.bind` | `agentId`、`source`（EXPLICIT / CONTINUED_TASK / INTENT / CURRENT_TASK / CONTINUITY / SOLE） |
| DECISION | `agent_unbound` | `DefaultAgentResolver.failNoMatch` | `reason`（AGENT_MISSING / AGENT_NONE）、`agentId`；AGENT_MISSING 为 ERROR，AGENT_NONE 为 WARN |
| TASK | `opened` | `DefaultTaskLifecycle.openTask` | `taskId`、`mode`、`agentId`、`previousTaskId` |
| TASK | `abandoned` | `DefaultTaskLifecycle.prepare`（放弃上一任务处） | `taskId`、`mode`（REPLACE_TASK / SWITCH_AGENT / CANCEL_TASK） |
| TASK | `missing` | `DefaultTaskLifecycle.prepare`（续跑但任务不存在） | `mode`，ERROR |
| HYDRATE | `loaded` | `DefaultHydrator.hydrate` | `agentId`、`skillCount`、`skills`；没有可用技能时 WARN |
| PLAN | `skipped` | `DefaultPlanner.plan` | `reason`（NO_DECISION / LOAD_EXISTING / NO_PLAN）、`mode` |
| PLAN | `planned` | `DefaultPlanner.plan` | `source`（LLM / SINGLE_REACT / DEGRADED）、`candidates`、`nodeCount`、`skills`、`firstReady` |
| PLAN | `failed` | `DefaultPlanner.plan` | `reason`（MISSING_TASK_OR_SKILLS / EMPTY_CATALOG / NO_CANDIDATE / EMPTY_STEPS / PROVIDER）及相关字段，ERROR |
| PLAN | `candidates_unparsed` / `candidates_failed` | `DefaultPlanner`（候选筛选） | `error`；解析失败按空候选、调用失败退回全量候选，WARN |
| PLAN | `llm_failed` | `DefaultPlanner`（规划模型） | `error`；随后走候选串行保底，WARN |
| PLAN | `target_dropped` | `DefaultPlanner`（校验规划结果） | `nodeId`、`skillKey`、`target`；target 不是该技能的写工具被丢弃，WARN |
| GRAPH | `next` | `DefaultGraphKernel.next` | `step`（`GraphStep` 实现类型：ExecuteWave / WakeFocus / CloseByAction / ResolveIdle）；ExecuteWave 带 `nodeIds`，WakeFocus 带 `nodeId`、`action`、`hasInput`，CloseByAction 带 `action` |
| GRAPH | `task_missing` | `DefaultTaskGraphExecutor` | 执行时没有任务，ERROR |
| NODE | `node_start` | `DefaultNodeRunner.run` | `skillKey`、`action` |
| NODE | `node_ready` | `DefaultNodeRunner`（历史与工具组好之后） | `historySize`、`toolCount`、`tools` |
| NODE | `node_end` | `DefaultNodeRunner.run` | `status`、`errorCode`、`durationMs` |
| NODE | `delegate_done` | `DefaultRuntimeStateFacade`（结算委派等待） | `childId`、`skillKey`、`status`、`errorCode`；子节点未成功时 WARN |
| NODE | `target_claimed` | `DefaultToolInvoker`（节点无完成目标时的首个写调用） | `nodeId`、`target` |
| NODE | `finished_without_target` | `ControlBuiltinTools.finish` | `nodeId`、`skillKey`；技能有写工具、节点却无完成目标就结束 |
| REACT | `round` | `DefaultReActLoop` | `round`、`maxRounds`、`historySize`、`toolCount` |
| REACT | `no_tool_calls` | `DefaultReActLoop`（模型未发起工具调用，提醒并记一次无进展） | — |
| REACT | `exited` | `DefaultReActLoop` | 控流退出：`status`、`checkpointType`、`missing`；停滞或轮次用尽：`status=FAILED`、`reason`（STALLED / MAX_ROUNDS），ERROR |
| TOOL | `invoked` | `DefaultToolInvoker.invoke` | `tool`、`outcome`（SUCCESS/FAILED/REJECTED/DEFERRED，见 TOOL_OBSERVATION.md）、`errorCode`（`RuntimeErrorCode` 枚举名，如 APPROVAL_REQUIRED）、`blockedBy`（被拦截器拦下时为拦截器类名）、`durationMs`、`args`（`ArgsBrief`）；outcome=FAILED 时 WARN |
| TOOL | `invoked` | `DefaultReActLoop`（工具不在本节点可见表） | `tool`、`outcome=REJECTED`、`errorCode`（CATALOG_DENIED） |
| TOOL | `repeat_warned` | `FingerprintInterceptor` | `tool`；相同调用再重复一次将熔断，WARN |
| MODEL | `call` / `call_failed` / `payload` | `DefaultModelGateway.chat` | 见第 7 节；`call` 另含 `toolNames` |
| FINALIZE | `reply` | `DefaultGoalGate.present` | `source`（NODE 直接采用节点结论 / VERIFIED 模型核验达成 / UNMET 模型判定未达成 / JOINED 拼接节点结论 / NOT_SUCCEEDED 未成功收口）、`replyChars` |
| FINALIZE | `goal_unmet` | `DefaultGoalGate.present` | `goal`；模型判定用户目标未达成，任务状态不变，WARN |
| FINALIZE | `verify_failed` | `DefaultGoalGate.present` | `error`；模型核验异常，退回拼接节点结论，WARN |
| FINALIZE | `turn_done` | `DefaultTurnPipeline.run` | `status`、`mode`、`taskStatus`、`replyChars`、`durationMs`；本轮失败时 ERROR |
| STATE | `size` | `StateSizeProbe`（每次 flush） | `payloadBytes`、`evidenceBytes`、`spaceBytes`、`priorBytes`、`writesBytes`、`opCount` |
| STATE | `call_update_deferred` | `EvidenceFlow`（预写超时后追平库表失败） | `evidenceId`、`error`；保留 Redis，轮末再追，WARN |

---

## 9. 现有日志迁移

| 现有日志 | 处理 |
|---|---|
| `[Dispatcher] begin / done`（整份 JSON） | 删除，由 ENTRY `result`、DECISION `settled`、`agent_bound` 代替 |
| `[BundleLoader] loaded / empty bundle`（整份技能 JSON） | 删除，由 HYDRATE `loaded` 代替；契约不可用的 ERROR 日志保留 |
| `[IntentAudit]` | 改为 DECISION `settled` |
| `[Planner]` 各里程碑 | 改为 PLAN `skipped` / `planned` / `failed` |
| `[GraphKernel] next ...`（含 `userInput`） | 改为 GRAPH `next`，不再输出用户原文 |
| `[NodeRunner] start / prepared / react done` | 改为 NODE `node_start` / `node_ready` / `node_end`；节点内循环结果见 REACT `exited` |
| `[ReAct] round` | 改为 REACT `round` |
| `[ReAct] model reply`（含模型正文） | 删除，由 MODEL `call` 代替；正文只在完整内容中出现 |
| `[ReAct] repair blocked` / `exited` | 拦截已删除；`exited` 改为 REACT `exited` |
| `[ReAct] invoke` / `tool result` | 删除，由 TOOL `invoked` 代替 |
| `[StateSize]` | 改为 STATE `size` |
| `[Delegate] result` | 改为 NODE `delegate_done` |
| `[IntentClassifier]`、`[MCP]`、`[SSE]`、`[Pipeline] error` 等 | 保留为普通日志 |

---

## 10. 代码改动

| 类 | 改动 |
|---|---|
| `support.trace.TraceStage` | 新增（12 个环节） |
| `domain.entry.EntryOutcome` / `EntryResult.outcome()` | 新增：入口结论 |
| `support.trace.Trace` | 新增：`at`、`enabled`、`bind`；唯一持有 logger（`@Slf4j(topic)`），提供包内可见的四个级别输出方法 |
| `support.trace.TraceEvent` | 新增：`kv`、`body`、`debug` / `info` / `warn` / `error`；空操作实例 |
| `support.trace.TraceScope` | 新增：`turn`、`node`、`bindTask`、`close` |
| `engine.model.ModelUse` | 新增 |
| `engine.model.ModelGateway` / `DefaultModelGateway` | `chat` 增加 `ModelUse` 参数；摘要、异常判定、完整内容 |
| `config.RuntimeProperties` | 新增 `Trace`（含 `Model`、`Payload`）；删除 `State.stateSizeLogEnabled` |
| `config.RuntimeConfig` | 启动时 `Trace.bind(properties.getTrace())` |
| `service.impl.ConversationServiceImpl` | 开启本轮轨迹作用域；锁内流程抽为 `runLocked` |
| 第 8 节各埋点所在类 | 按清单打点，按第 9 节迁移旧日志 |
| `engine.state.StateSizeProbe` / `TurnFlushPlan` | 体量统计改为 STATE 轨迹，由环节开关控制 |
| 模型调用方（5 处） | 传入 `ModelUse` |
| `logback-spring.xml` | 声明 `g2rain.agent.trace` logger |
| `application.yml` | 增加 `trace` 配置；覆盖 `logging.pattern.console` / `file`，增加定位信息；删除 `state-size-log-enabled` |

---

## 11. 验收与测试

### 11.1 验收用例

| # | 操作 | 期望 |
|---|---|---|
| 1 | 新对话发一句办事请求 | 按 traceId 过滤，依次看到 ENTRY → MODEL(INTENT) → DECISION → TASK → HYDRATE → PLAN → GRAPH → NODE → REACT / TOOL / MODEL → FINALIZE，每行都带会话 ID；建任务后带任务 ID，节点内带节点 ID |
| 2 | 同一轮的普通日志 | 也带会话、任务、节点 ID |
| 3 | 意图模型返回空 | MODEL `call` 为 WARN 且 `abnormal=EMPTY`，随后两行完整内容 |
| 4 | `stages` 去掉 `REACT` | 不再出现 `[Trace][REACT]`，其它环节不受影响 |
| 5 | `payload=NONE` 且模型返回空 | 只有 WARN 摘要，没有完整内容 |
| 6 | 请求带调试头（`DEBUG_PRINT_LEVEL=TRUE`） | 该请求每次模型调用都输出完整内容，其它请求不受影响 |
| 7 | 配置中心修改 `stages` | 无需重启，下一轮生效 |
| 8 | 全文检查默认配置下的日志 | 不出现用户原文、模型正文、工具参数原值 |

### 11.2 单元测试

| 测试类 | 覆盖 |
|---|---|
| `TraceEventTest` | 字段格式：枚举、集合、引号、截断、`null`；环节关闭时空操作 |
| `TraceScopeTest` | `turn` 清理残留并在结束时恢复；`node` 嵌套恢复；`bindTask` 在 turn 结束后清除 |
| `DefaultModelGatewayTest` | 异常判定 EMPTY / LENGTH / ERROR；三档 `payload` 与单请求 DEBUG 的输出行为 |

---

## 12. 联调排障指引

### 12.1 基本做法

1. 从前端响应或网关日志拿到本轮 traceId（SSE 的流 ID 即 traceId）。
2. 按 traceId 过滤日志，再按 `[Trace]` 过滤，得到本轮链路；去掉 `[Trace]` 过滤即可看到同一时间线上的普通日志与异常堆栈。
3. 需要看模型原文时，让该请求带调试头（`DEBUG_PRINT_LEVEL=TRUE`）重放；无需改全局配置。

### 12.2 现象与对应的轨迹

| 现象 | 先看 | 判断要点 |
|---|---|---|
| 回复"还有一项操作等待您确认" | DECISION `settled` | `writePending=true` 且 `proposed` 为 CONTINUE_TASK 或空；看上一轮是否停在审批或回查断点 |
| 意图判断不符合预期 | MODEL `call use=INTENT`、DECISION `settled` | `proposed` 与 `settled` 不同说明被校正；`abnormal=EMPTY` 时看紧随的 `payload` 原文 |
| 选错数字员工 / 提示没有合适的数字员工 | DECISION `agent_bound` / `agent_unbound` | `source` 说明人选来自哪一级；`agent_unbound` 的 `reason` 与 `agentId` |
| 补参回复后没有续跑 | ENTRY `result` | 应为 `outcome=CONTINUE action=REPLY`；若为 ACCEPT，看断点类型是否为 FOLLOW_UP |
| 提示"这件事暂时处理不了" | HYDRATE `loaded`、PLAN `failed` | `skillCount=0` 说明技能未装上；PLAN `reason` 说明失败在哪一步 |
| 计划不对 | PLAN `planned` | `source` 是否为 DEGRADED；`candidates` 与 `skills` 是否符合预期 |
| 节点反复不推进 / 失败 | REACT `round`、`no_tool_calls`、`exited`，TOOL `invoked` | 被拒的 `errorCode`；`exited reason=STALLED / MAX_ROUNDS` |
| 工具没被调用或调用失败 | NODE `node_ready`、TOOL `invoked` | 工具是否在 `tools` 中；`outcome`、`blockedBy` 与 `errorCode`（不可见为 CATALOG_DENIED） |
| 模型返回异常 | MODEL `call` 的 WARN 行 | `abnormal`、`finishReason`、`completionTokens`；紧随的 `payload` 两行 |
| 最终回复内容不对 | FINALIZE `reply` | `source` 说明回复取自证据、确定性草稿还是模型润色 |
| 响应慢 | 各行 `durationMs`、FINALIZE `turn_done` | 对比 MODEL、TOOL、NODE 的耗时 |

