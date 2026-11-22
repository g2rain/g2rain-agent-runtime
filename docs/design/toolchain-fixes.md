# 第二批 B4：工具链杂项（P2-13、P2-14②、P2-15、P2-18、P2-20、P2-21）

| 项 | 内容 |
|---|---|
| 状态 | 设计待确认 |
| 修复条目 | 扶正清单 P2-13、P2-14②、P2-15（需要修的部分）、P2-18、P2-20、P2-21 |
| 涉及类 | `InputBinder`、`ProviderNodes`、`WriteVerifier`、`VerifySpec`、`EvidenceFlow`、`IdempotencyKeyBuilder`、`RuntimeUtils`（P2-13、P2-20）；`DefaultNodeScope`（P2-14②）；`DefaultNodeRunner`、`DefaultReActLoop`、`McpToolCallbackProvider`、`RuntimeStateFacade`（P2-15、P2-18）；`SseEventSink`（P2-20）；`application.yml`（P2-21） |
| 不涉及 | `HaltResolver`（根因在 `addEvidence`，修了以后它自然正确）、P2-9（派发前 `argsHash` 自比，需拍板）、表结构 |
| 关联文档 | [契约规范](../CONTRACT_SPEC.md)；[状态模型](../STATE_SPEC.md) 2.2、3.3、第 8 节；[执行过程与失败透传](progress-and-failure.md) |

---

## 1. P2-13：`from` 前缀散落解析、回落幂等键重复实现

### 1.1 `from` 前缀

`InputBinder.resolveOne`、`ProviderNodes.alreadyBound` 各自用字面量 `"session."`、`"shared."`、`"prior."`、`"outputs."` 判断前缀并截取。项目里已经有统一的解析器 `ValueRef.parse`（`spec` 包）。

**修复**：两处都改为先 `ValueRef.isKnown(from)`，再 `ValueRef.parse(from)`，按 `bag()` 分支：

| 方法 | `SESSION` | `SHARED` | `PRIOR` | `OUTPUTS` | 其他 / 未知前缀 |
|---|---|---|---|---|---|
| `InputBinder.resolveOne` | 会话共享 | 任务共享 | `priorValue` | `nearestUpstream(path)` | `nearestUpstream(name)`（与现状一致） |
| `ProviderNodes.alreadyBound` | 会话共享非空 | 任务共享非空 | false | false | false |

`WriteVerifier.verifyArgs` 中的 `"call."` 只用于 `verify.args`，不属于 `ValueRef`。在 `VerifySpec` 中新增常量 `CALL_PREFIX = "call."`，`verifyArgs` 改用这个常量。

### 1.2 回落幂等键

`EvidenceFlow.liveKey` 的回落分支（契约没有配置幂等键时）与 `IdempotencyKeyBuilder.effectiveKey` 的回落分支是同一个算法（`sha256(工具名|参数哈希)`），写了两遍。

**修复**：在 `RuntimeUtils` 新增

```java
/**
 * 工具调用的回落幂等键：sha256(工具名|参数哈希)；工具名为空时只用参数哈希。
 *
 * <pre>{@code
 * String key = RuntimeUtils.callKey("cms_article_save", args);
 * }</pre>
 */
public static String callKey(@Nullable String toolName, @Nullable JsonNode args)
```

`EvidenceFlow.liveKey` 和 `IdempotencyKeyBuilder.effectiveKey` 的回落分支都改为调用它。结果与现在逐字节相同，已有证据上的幂等键不受影响。

## 2. P2-14②：证据更新时被挪到列表末尾

**问题**：`DefaultNodeScope.addEvidence` 的实现是“先删除同 ID 的证据，再追加到末尾”。证据状态被改写时（结算、`UNKNOWN_PRE_CALL` → `CANCELLED`、中断规整、核实结果），这条旧证据会被挪到最后。于是 `HaltResolver.lastCallUnresolved` / `lastCallAmbiguous` 用 `getLast()` 取到的不再是“最近一次调用”，历史重建的顺序也会被打乱。

**修复**：`addEvidence` 改为**已存在就原位替换，不存在才追加**，列表始终按证据的创建顺序排列。`HaltResolver` 不用改。

## 3. P2-15：杂项

| # | 位置 | 修复 |
|---|---|---|
| a | `DefaultNodeRunner.dispatchPending` | 不再调用 `nodeToolset.of`（会再拉一次 MCP 清单），改为接收 `proceed` 中已经算好的 `tools`（新增参数） |
| b | `DefaultReActLoop` 畸形工具调用分支 | 计为一次无进展：`malformedNudge` 之后调用 `stall.recordStall()`，超限则返回 `stalledFail`；删除“不计无进展”的注释。畸形调用反复出现本来就是没有进展，不该只靠总轮次兜底 |
| c | `McpToolCallbackProvider.listToolsWithRetry` | 日志改为 `"MCP listTools access failed ({}/{})"`，参数为 `i + 1, attempts`；只有还会重试时才写 “will retry” |
| d | `RuntimeStateFacade.open` 的 Javadoc | 把“处理 UNKNOWN 证据”改为“未收口的 `UNKNOWN_PRE_CALL` 证据改为 `CANCELLED`” |
| e | STATE_SPEC 2.2 flush 操作表 | 删除 `LREM` 一行（`flush.lua` 没有实现） |
| f | STATE_SPEC 3.3 `tokens` 一行、第 8 节约束 6 | `tokens` 已经在统计（`NodeScope.spend`）：3.3 改为“本节点模型调用累计 token”，第 8 节删除约束 6 |

## 4. P2-18：客户端不按 `toolNames` 过滤

`McpToolCallbackProvider.getToolCallbacks` 在过滤空名之后，再加一层 `.filter(tool -> toolNames.contains(tool.name()))`。注册中心多返回的工具直接丢弃，并对每个被丢弃的工具记一条 Trace（`TraceStage.TOOL`，事件名 `mcp_tool_unexpected`，带工具名）。这是 CONTRACT_SPEC 1.3“可调用范围以 toolNames 为准”的纵深防御。

## 5. P2-20：SSE 的 `streamId` 与回调线程中的 traceId 可能不一致

**问题**：网关没有注入 traceId 时，`RuntimeUtils.requireTraceId()` 每次调用都会生成一个新的 UUID。`SseEventSink.register` 在请求线程中生成 `streamId`，回调虚拟线程里又调用一次，本轮编排中的其他地方也各自调用。几个值互不相同，事件就路由不到这条 SSE 连接上。

**修复**：

1. `RuntimeUtils.requireTraceId()`：上下文中没有 traceId 时，生成后**写回上下文**（`PrincipalContextHolder.setTraceId`，上下文不存在时它本身是空操作）。这样同一请求后续的调用拿到的都是同一个值；`ContextExecutors.startVirtual` 会把同一个上下文带进虚拟线程。
2. `SseEventSink.register`：虚拟线程里直接使用外层的 `streamId`，不再调用 `requireTraceId()`。
3. 方法 Javadoc 补充“生成后写回上下文”。

## 6. P2-21：无人读取的配置

从 `g2rain-agent-runtime-startup/src/main/resources/application.yml` 中删除 `react.repair-max-rounds`、`goal-gate.llm-enabled`、`goal-gate.min-deterministic-chars` 三项（`RuntimeProperties` 中本来就没有对应字段，REFACTOR_PLAN 已记录删除）。`docs/design/progress-and-failure.md` 中“`max-rounds` / `repair-max-rounds`”改为只写 `max-rounds`。

---

## 7. 任务卡（交给 Cursor）

**卡片 B4：工具链杂项**

**前提**：先读本文全文、CONTRACT_SPEC 中 `from` 前缀与 `verify.args` 的说明，以及 `docs/development/code-conventions.md` 的“编码细则”。只改本文开头表格中列出的类、配置和文档；P2-9 不在本卡。

**步骤**：

1. 按第 1～6 节修改。
2. 补齐下表的用例。
3. 同步文档（STATE_SPEC 2.2、3.3、第 8 节；progress-and-failure.md）。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| T1 | `InputBinder`：`from` 分别为 `session.a`、`shared.a`、`prior.a`、`outputs.a`、未知前缀 | 分别取会话共享、任务共享、前情、上游产出；未知前缀按输入名取最近上游（与现状一致） |
| T2 | `ProviderNodes.alreadyBound`：`session.a` 已有值 / `shared.a` 为空 / `prior.a` | true / false / false |
| T3 | `RuntimeUtils.callKey` 与改动前 `liveKey` 回落分支 | 同一组工具名和参数得到相同的值（用硬编码的期望值断言） |
| T4 | `IdempotencyKeyBuilder.effectiveKey`：契约没有幂等键 | 等于 `RuntimeUtils.callKey(...)` |
| T5 | `addEvidence`：依次添加 A、B，再用新状态更新 A | 顺序仍为 A、B，A 为新状态 |
| T6 | `HaltResolver`：最近一次调用 B 成功且有多条候选，之后旧证据 A 被改为 `CANCELLED` | 仍判为 `AMBIGUOUS`（按 B 判断） |
| T7 | `dispatchPending` | `nodeToolset.of` 在一次 `proceed` 中只被调用一次 |
| T8 | `DefaultReActLoop`：连续出现畸形工具调用，达到无进展上限 | 节点 `FAILED`，`errorCode` 为 `NODE_STALLED` |
| T9 | `McpToolCallbackProvider`：注册中心返回了 `toolNames` 之外的工具 | 结果中不包含该工具 |
| T10 | `RuntimeUtils.requireTraceId`：上下文中没有 traceId，连续调用两次 | 两次返回同一个值，且上下文中的 traceId 等于它 |

**验收**：T1～T10 全部通过；原有测试仍然通过；IDEA 无新增告警；文档已同步。

---

## 8. 补充：P2-9 派发前的参数摘要比对（2026-11-22 决定：删除）

**问题**：`DefaultNodeRunner.dispatchPending` 在用户确认后，用 `pending.args` 重算哈希，再与 `pending.argsHash` 比较。两者来自同一个 `PendingAction`，而 `argsHash` 本来就是 `WriteGateInterceptor` 用这份 `args` 算出来的，所以结果恒等，这个检查从不生效。审批卡展示的参数和派发的参数也来自同一个断点，没有一个独立的“用户看到的版本”可供比对。

**决定**：删除这个检查以及只为它存在的字段。

| 位置 | 修改 |
|---|---|
| `PendingAction` | 删除 `argsHash` 分量；两参数的便捷构造随之调整；Javadoc 同步 |
| `WriteGateInterceptor` | 构造 `PendingAction` 时不再传 `RuntimeUtils.argsHash(args)` |
| `DefaultNodeRunner.dispatchPending` | 删除比对代码块，以及只为它存在的 import |
| `RuntimeErrorCode` | 删除 `ARGS_HASH_MISMATCH`（Infra 中 `agent_runtime.40913` 的 i18n 行保留不动，不改种子数据） |
| STATE_SPEC 3.2 `PendingAction` 表 | 删除 `argsHash` 一行（`EvidenceHead.argsHash` 与 `tool_call_record.args_hash` 是另一回事，保留） |

已落库断点 JSON 中多出的 `argsHash` 字段，反序列化时会被忽略。

**用例**：`DefaultNodeRunnerDispatchTest` 中确认派发的用例照常通过；如有断言 `ARGS_HASH_MISMATCH` 的测试，一并删除。
