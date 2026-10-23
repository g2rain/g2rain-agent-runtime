# 执行过程时间线与失败原因透传

| 项 | 内容 |
|---|---|
| 状态 | 第 1～7 步已完成，待联调；第 8 步（agent-app 会话追踪）后续 |
| 涉及项目 | g2rain-agent-runtime、g2rain-model-nexus、g2rain-chat-shell |
| 关联文档 | [SSE 事件](../SSE_EVENTS.md)、[链路轨迹日志](../TRACE_LOGGING.md)、[工具结果与进展判定](../TOOL_OBSERVATION.md) |

---

## 1. 背景

1. **中间过程不可见**。一轮对话只在 3 处发 `PROGRESS`：开始时的"正在处理"、节点开始和准备（`DefaultNodeRunner`）、工具开始（`ToolEvents`）。意图判断、选员工、规划结果、ReAct 轮次、模型调用、工具成败与耗时、目标校验结论只写在 Trace 日志里，前端看不到。
2. **失败原因被吞**。余额不足、参数超限、限流这类原因，在三层被抹掉：
   - model-nexus：`OpenAiCompatibleServiceImpl` 的三处 `catch (Exception)` 把上游异常统一包成 `REMOTE_SERVICE_ERROR`，HTTP 状态按 5xx 返回，上游的 400 / 402 / 429 和厂商错误码（如 `insufficient_quota`）都丢了。
   - agent-runtime 网关：`DefaultModelGateway` 收到 4xx 只包成 `DEPENDENCY_UNAVAILABLE`，不解析错误体；收到 5xx 还会重试。
   - agent-runtime 兜底：`DefaultTurnPipeline`、`ConversationServiceImpl.runLocked`、`DefaultFinalizer`、`DefaultGraphTransitionReducer`、`SseEventSink` 这 5 处统一换成"处理没有成功，请稍后再试。"。
3. 现有的 `PROGRESS` 覆盖式展示和进度气泡是过渡方案。项目未上线，本方案**整体替换**，不做兼容。

## 2. 目标与非目标

**目标**

- 每一轮的关键环节都以步骤形式推给前端，前端按时间线逐条展示。
- 步骤入库，刷新页面或打开历史会话后能完整还原。
- 失败时给出可理解的原因和追踪号，管理员能看到上游原文。

**非目标**

- 执行中刷新页面时，续看正在进行的步骤（需要从 Redis 实时读取，后续再做）。
- model-nexus 对外开放时的入参扩展（另行设计）。

---

## 3. 执行过程时间线

### 3.1 事件模型

只保留 `PROGRESS` 和 `RESULT` 两种事件，不新增事件类型。`PROGRESS` 不再是一句覆盖式文案，而是携带一个**步骤**对象 `step`（与 RESULT 的 `status` 等字段不冲突），`step` 字段如下：

| 字段 | 类型 | 说明 |
|---|---|---|
| `stepId` | String | 步骤 ID；同一步骤的开始和结束用同一个 ID |
| `parentStepId` | String | 上级步骤；用于"节点 → 轮次 → 模型 / 工具"的层级展示，顶层为空 |
| `stage` | String | 环节，见 3.2 |
| `status` | String | `RUNNING` / `DONE` / `FAILED` |
| `title` | String | 一句话标题，如"调用 查询订单" |
| `detail` | String | 补充说明，如"成功"、"失败：订单号不存在"；可空 |
| `elapsedMs` | Integer | 本步骤耗时；仅结束时有值 |
| `errorCode` | String | 失败时的错误码；仅 `FAILED` |
| `nodeId` / `round` | String / Integer | 所属节点、推理轮次；可空 |

实现要点：

- 本轮由 `StepEvents.TURN`（JDK 25 `ScopedValue`）在会话锁内绑定，各环节调用 `StepEvents.start / done / fail`，无需传递本轮视图；步骤记在 `TurnScope.progress()`（`TurnProgress`）。
- 进行中步骤按栈维护：新步骤的上级是最内层进行中步骤；结束一个步骤时其下子步骤一并结束；开启某环节新步骤时，仍在进行的同环节步骤自动完成（ReAct 下一轮开始即上一轮结束）。
- 出 RESULT 前 `finishAll` 结束全部进行中步骤，失败轮次按 FAILED 带错误码。
- 原 `GraphEffect.Publish` 与"正在处理"首帧移除，首帧为 ROUTE 开始帧。

前端规则：**按 `stepId` 合并**。首次出现时追加一行，再次出现时更新同一行的状态、说明和耗时。不存在覆盖旧消息的逻辑。

### 3.2 环节（stage）

| stage | 含义 | 上级 |
|---|---|---|
| `ROUTE` | 意图判断与选员工 | 顶层 |
| `PLAN` | 规划节点 | 顶层 |
| `NODE` | 执行一个节点 | 顶层 |
| `ROUND` | ReAct 的一轮 | `NODE` |
| `MODEL` | 一次模型调用 | `ROUTE` / `PLAN` / `ROUND` / `VERIFY` |
| `TOOL` | 一次工具调用 | `ROUND` |
| `VERIFY` | 目标校验 | 顶层 |

### 3.3 发布点

统一由新增的 `StepEvents` 发布（仿照现有 `ToolEvents`），各处只调用 `start` / `done` / `fail`。

| stage | 类 | 开始 | 结束示例 |
|---|---|---|---|
| ROUTE | `DefaultTurnDispatcher`、`DefaultIntentClassifier`、`DefaultAgentResolver` | 理解问题 | 新开任务，选中「客服助手」/ 继续上一个任务 / 恢复等待 |
| PLAN | `DefaultPlanner` | 规划步骤 | 规划 2 个节点：查询订单 → 发起退款 |
| NODE | `DefaultNodeRunner` | 节点：查询订单 | 完成 · 3.2s |
| ROUND | `DefaultReActLoop` | 第 2 轮 | 完成；结束时节点上标注"共 3 轮" |
| MODEL | `DefaultModelGateway` | 推理 | 推理 · 1.4s · 1,203 tokens |
| TOOL | `ToolEvents`（由 `DefaultToolInvoker` 触发） | 调用 查询订单 | 成功 · 820ms / 失败：订单号不存在 |
| VERIFY | `DefaultGoalGate` | 校验结果 | 目标达成 / 证据不足，补充一轮 |

约定：

- 模型调用**不显示模型名**（真实模型名需要隐藏，runtime 拿不到展示名称），只显示用途、耗时、token。用途沿用 `ModelUse`，中文文案：意图、选技能、规划、推理、校验。
- ReAct 轮次**不显示上限**。`max-rounds` / `repair-max-rounds` 只是防死循环的阈值。
- 本轮第一帧是 `ROUTE` 的开始帧，携带 `conversationId`，替代原来的"正在处理"。
- 内置工具（控流工具）不发步骤，与 `ToolEvents` 现有规则一致。

### 3.4 文案示例

```text
▸ 执行过程 · 12s
  ✓ 新开任务，选中「客服助手」
      推理 · 0.6s · 412 tokens
  ✓ 规划 2 个节点：查询订单 → 发起退款
  ✓ 节点：查询订单 · 3.2s · 共 2 轮
      第 1 轮 · 推理 1.4s · 调用 查询订单 成功 820ms
      第 2 轮 · 推理 0.9s
  ✗ 节点：发起退款
      第 1 轮 · 调用 发起退款 失败：余额不足

退款没有成功：账户余额不足，请充值后重试。（追踪号 a1b2c3）
```

### 3.5 排查信息

- chat 侧（SSE 与历史接口）对所有人只返回 `title`、`detail`、耗时与 `errorCode`，**不下发 `extra`**。
- `extra`（token 明细、工具参数摘要 `ArgsBrief`、上游错误原文）只入库，供 agent-app 的会话追踪页按会话或追踪号查看。
- 用户报问题时提供回复末尾的追踪号即可定位。

### 3.6 持久化

**不放进 `chat_message`**。`chat_message` 会加载进 `DialogueLog` 作为模型上下文，并按 `dialogueMax` 裁剪；过程记录放进去会污染上下文，也会被裁掉。新建 `chat_progress` 表：

- 一行一个步骤，按 `trace_id` 关联到这一轮；最终回复也带 `trace_id`，历史接口据此把回复和执行过程一起返回。
- 本轮步骤先暂存在 `TurnScope`，随 `persist` 与消息一起批量写入，复用现有的持久化与重试机制。
- 同一步骤以 `step_id` 唯一，开始和结束在内存中合并，只写最终状态。
- 本轮异常中断时，未结束的步骤以 `FAILED` 落库。

表结构见 `scripts/g2rain-agent-runtime.sql` 中的 `chat_progress`。

### 3.7 历史接口

`GET /chat/message/list` 的每条 AGENT 最终回复，附带本轮步骤列表 `progress[]`（字段同 3.1，按 `seq` 排序）；用户消息和旧数据没有该字段。

---

## 4. 失败原因透传

### 4.1 失败分类

新增 `FailureCategory`，失败时统一归到一类，生成给用户看的文案：

| category | 典型来源 | 用户文案 |
|---|---|---|
| `QUOTA` | 402、`insufficient_quota` | 模型服务额度不足，请联系管理员 |
| `AUTH` | 401 / 403 | 模型访问密钥无效，请联系管理员 |
| `PARAM` | 400、`context_length_exceeded` | 内容过长或参数不符合模型要求 |
| `CONFIG` | model-nexus 平台错误（404、`model_nexus.*`） | 模型未配置或未启用，请联系管理员 |
| `RATE_LIMIT` | 429 | 模型繁忙，请稍后重试 |
| `TIMEOUT` | 上游超时 | 模型响应超时，请稍后重试 |
| `TOOL` | MCP / 工具错误（`TOOL_*`、`MCP_*`） | 调用「工具名」失败：原因 |
| `INTERNAL` | 其余 | 处理没有成功，请稍后再试 |

### 4.2 model-nexus

1. 新增 `UpstreamException(status, code, message, provider)`。
2. `OpenAiCompatibleServiceImpl` 的三处 `catch (Exception)`（chat / embeddings / rerank）：能从 Spring AI 异常中取到上游状态码和响应体时，抛 `UpstreamException`；取不到（如网络中断）仍走 `REMOTE_SERVICE_ERROR`。
3. `OpenAiExceptionAdvice` 新增 `@ExceptionHandler(UpstreamException.class)`：按上游状态码返回，body 为标准 OpenAI error，`code` 为厂商错误码，`message` 为脱敏后的原文（去掉密钥、内网地址）。
4. `OpenAiErrorAssembler` 和其余处理方法不变。
5. **需要逐个验证**：OpenAI、Anthropic、Gemini 三个 Spring AI 客户端抛出的异常类型，以及能否取到状态码与响应体。

### 4.3 agent-runtime

1. `DefaultModelGateway.exchange`：4xx 不重试，解析 `OpenAiErrorResponseVo`，按状态码与 `code` 映射 `FailureCategory`，抛出带分类的 `BusinessException`；只有 5xx 和连接失败才重试。
2. 新增模型类错误码（`MODEL_QUOTA`、`MODEL_AUTH`、`MODEL_PARAM`、`MODEL_CONFIG`、`MODEL_RATE_LIMIT`、`MODEL_TIMEOUT`），替代模型调用场景下的 `DEPENDENCY_UNAVAILABLE`。
3. 工具失败沿用 `DefaultToolInvoker` 已有的观测分类（`TOOL_*` / `MCP_*`），在 `TOOL` 步骤上带出工具名和原因。
4. 有异常可取的兜底（`DefaultTurnPipeline`、`ConversationServiceImpl.runLocked`）：从异常链取 `FailureCategory` 生成文案，取不到才使用 `ERROR_GENERIC`；`errorCode` 写入 RESULT（第 5 步起同时写入失败步骤）。`DefaultFinalizer`、`DefaultGraphTransitionReducer`、`SseEventSink` 三处没有异常可取，保持通用文案。
5. 模型类失败为**致命失败**：意图判断（`DefaultIntentClassifier`）、选技能（`SkillSelector`）、规划（`PlanDrafter`）原有的降级分支遇到时直接上抛（`FailureCategory.throwIfFatal`），不再降级后继续执行。目标校验（`DefaultGoalGate`）仍降级为拼接结果，避免丢掉已完成的工作。
6. 504 / 超时不重试，避免超时被放大为多倍等待。

### 4.4 RESULT 新增字段

| 字段 | 说明 |
|---|---|
| `errorCode` | 失败时的错误码；仅 `FAILED` |
| `traceId` | 本轮追踪号（现为 `@JsonIgnore`，改为下发）；前端失败时显示末 6 位，便于对日志 |
| `progress` | 不在 RESULT 中重复下发，前端用已收到的 PROGRESS 组装 |

上游原文只写入失败步骤的 `extra`，在会话追踪页查看，不下发给 chat。

---

## 5. 前端（chat-shell）

1. 去掉进度气泡（`progressText` + 流光 + 独立计时）。
2. 每条 AI 回复上方新增可折叠的"执行过程"：
   - 执行中：折叠标题为最新的进行中步骤，带流光与计时，如"▸ 正在调用 查询订单 · 8s"。
   - 结束后：标题为"▸ 执行过程 · 12s"，失败时标红。
   - 展开后按 3.2 的层级缩进展示。
3. 实时和历史用同一个组件渲染：实时由 PROGRESS 按 `stepId` 合并，历史取接口返回的 `progress[]`。
4. 失败回复末尾显示追踪号。
5. 回复下方的"时间 · 耗时"保持不变。

---

## 6. 实施步骤

| 步骤 | 内容 | 项目 |
|---|---|---|
| 1 | 在 SQL 脚本中新建 `chat_progress`，评审表结构 | agent-runtime |
| 2 | 用户用生成器生成 `chat_progress` 的 CRUD | agent-runtime |
| 3 | 失败透传：`UpstreamException`、异常拦截 | model-nexus |
| 4 | 失败透传：网关分类、错误码、5 处兜底、RESULT 字段 | agent-runtime |
| 5 | 步骤事件：`TurnEvent` 改造、`StepEvents`、各发布点、`TurnScope` 暂存与落库、历史接口 | agent-runtime |
| 6 | 同步 [SSE 事件](../SSE_EVENTS.md) 文档（第 3、4、7、8、9、10、11 节） | agent-runtime |
| 7 | 执行过程组件、事件合并、历史渲染、追踪号 | chat-shell |
| 8 | 会话追踪页：按会话 / 追踪号查看完整时间线与 `extra`（后续） | agent-app |

每一步完成后停下，确认再进行下一步。

---

## 7. 待定事项

暂无。

## 8. 评审结论

| 日期 | 结论 |
|---|---|
| 2026-10-23 | 只保留 PROGRESS，改为结构化步骤；时间线逐条保留并入库到独立表 `chat_progress`；模型步骤不显示模型名，轮次不显示上限；排查信息 `extra` 不下发 chat，只在 agent-app 会话追踪查看；失败分类透传，model-nexus 保留上游状态码与错误码；整体替换，不做兼容 |
