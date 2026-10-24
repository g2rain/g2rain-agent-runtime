# 对话流式接口规范（SSE）

本文档面向前端，说明如何调用对话接口、如何解析流式事件、如何渲染和回应。前端需处理三种事件：执行步骤 `PROGRESS`、会话标题 `TITLE` 和本轮结果 `RESULT`。

---

## 目录

1. [接口](#1-接口)
2. [请求](#2-请求)
3. [响应流](#3-响应流)
4. [事件结构](#4-事件结构)
5. [结果状态与界面行为](#5-结果状态与界面行为)
6. [动作与候选答案](#6-动作与候选答案)
7. [场景示例](#7-场景示例)
8. [前端实现](#8-前端实现)
9. [历史消息](#9-历史消息)
10. [异常与边界](#10-异常与边界)
11. [文案参考](#11-文案参考)

---

## 1. 接口

| 项 | 值 |
|---|---|
| 方法 | `POST` |
| 路径 | `/v1/chat/completions` |
| 请求体 | `application/json` |
| 响应 | `text/event-stream` |

一次请求对应一轮对话。服务端推送若干 `PROGRESS`（每个步骤开始一帧、结束一帧），新建会话时另推一帧 `TITLE`；`PROGRESS` 与 `TITLE` 可在 `RESULT` 之前任意时刻出现，最后推送且只推送一个 `RESULT`，然后关闭连接。

---

## 2. 请求

### 2.1 请求头

身份信息由网关注入，前端经网关调用时无需自行设置。

| 请求头 | 说明 |
|---|---|
| `X-TRACE-ID` | 本轮请求 ID，**每次请求必须唯一**。服务端用它把事件路由到这条连接 |
| `X-ORGAN-ID`、`X-USER-ID` 等 | 登录身份，由网关注入 |

### 2.2 请求体

```json
{
  "channel": "web",
  "agentId": 10001,
  "conversationId": "6e96adc4d6c34754beed06a017978f77",
  "userInput": "帮我把这篇文章发布出去",
  "action": null,
  "model": "qwen3-32b"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `channel` | String | 是 | 来源渠道，例如 `web`、`app` |
| `conversationId` | String | 否 | 会话 ID。新会话不传，从返回的第一条事件中取得，之后每轮都要带上 |
| `agentId` | Long | 否 | 指定数字员工。不传时由服务端根据内容选择 |
| `userInput` | String | 否 | 用户输入的文字，或点选的候选答案 |
| `action` | String | 否 | 回应上一轮结果中的按钮时填写，取值见第 6 节；普通发言时不传 |
| `model` | String | 否 | 本轮选用的模型名，透传给模型服务；不传时由模型服务按默认路由。每轮可不同，记录在本轮用户消息上 |

### 2.3 三种典型请求

| 场景 | `userInput` | `action` |
|---|---|---|
| 普通发言 | 用户输入 | 不传 |
| 回答追问、点选候选答案 | 回答内容 | `REPLY` |
| 点击按钮（确认、拒绝、取消） | 不传 | `CONFIRM` / `REJECT` / `CANCEL` |

---

## 3. 响应流

响应为标准 SSE 格式。每个事件由 `event:` 行（事件类型）和 `data:` 行（JSON）组成，事件之间以空行分隔：

```text
event: PROGRESS
data: {"type":"PROGRESS","conversationId":"6e96…","traceId":"…","step":{"stepId":"s1","stage":"ROUTE","status":"RUNNING","title":"理解问题"}}

event: PROGRESS
data: {"type":"PROGRESS","conversationId":"6e96…","traceId":"…","step":{"stepId":"s1","stage":"ROUTE","status":"DONE","title":"理解问题","detail":"新开任务，选中「CMS 助手」","elapsedMs":620}}

event: RESULT
data: {"type":"RESULT","conversationId":"6e96…","status":"DONE","text":"目前共有 3 个标签：产品动态、技术分享、公司新闻。"}

```

心跳：长时间没有事件时，服务端每 15 秒发送一行注释保持连接：

```text
: ping
```

注释行以 `:` 开头，不是事件，前端忽略即可。

---

## 4. 事件结构

所有事件共用同一个结构，没有值的字段不会出现在 JSON 中。

| 字段 | 类型 | PROGRESS | RESULT | 说明 |
|---|---|---|---|---|
| `type` | String | ✓ | ✓ | `PROGRESS`、`TITLE` 或 `RESULT`，与 `event:` 行相同 |
| `conversationId` | String | ✓ | ✓ | 会话 ID |
| `text` | String | | ✓ | `RESULT`：回复正文；`TITLE`：会话标题 |
| `step` | Object | ✓ | | 执行步骤，见 4.1 |
| `status` | String | | ✓ | 本轮结果状态，见第 5 节 |
| `actions` | String[] | | 仅 `WAITING` | 用户可执行的动作，见第 6 节 |
| `suggestions` | String[] | | 可选 | 候选答案，见第 6 节 |
| `createdAtMs` | Long | | 有回复时 | 回复的发言时间（epoch ms），与历史消息一致 |
| `elapsedMs` | Integer | | 有回复时 | 本轮耗时（毫秒），从服务端开始处理本次请求算起，与历史消息一致 |
| `errorCode` | String | | 仅 `FAILED` | 失败时的业务错误码，如 `agent_runtime.40201`（模型额度不足） |
| `traceId` | String | ✓ | ✓ | 本轮追踪号；失败时建议展示末 6 位，便于反馈问题时定位日志 |

### 4.1 PROGRESS：执行步骤

```json
{"type":"PROGRESS","conversationId":"6e96…","traceId":"…",
 "step":{"stepId":"s7","parentStepId":"s6","stage":"TOOL","status":"DONE","title":"调用 查询标签","detail":"成功","nodeId":"n1","elapsedMs":820}}
```

`step` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `stepId` | String | 步骤 ID；同一步骤的开始与结束共用 |
| `parentStepId` | String | 上级步骤；顶层不出现 |
| `stage` | String | 环节：`ROUTE` 理解问题、`PLAN` 规划、`NODE` 节点、`ROUND` 推理轮次、`MODEL` 模型调用、`TOOL` 工具调用（含完成、追问、申请确认、委派等控流动作，如"完成""委派：知识问答"）、`VERIFY` 校验结果 |
| `status` | String | `RUNNING` / `DONE` / `FAILED` |
| `title` | String | 标题，如"调用 查询标签"、"第 2 轮" |
| `detail` | String | 补充说明，如"成功"、"规划 2 个节点：查询文章 → 添加标签"；可能不出现 |
| `nodeId` | String | 所属节点；可能不出现 |
| `round` | Integer | 推理轮次；仅 `ROUND` |
| `elapsedMs` | Integer | 耗时；结束后才有 |
| `errorCode` | String | 失败错误码；仅 `FAILED` |

渲染规则：

- **按 `stepId` 合并**：首次出现追加一行，再次出现更新同一行的状态、说明与耗时。
- **按 `parentStepId` 缩进**：层级为"节点 → 轮次 → 模型 / 工具"，理解问题与规划下也可能挂模型调用。
- 每个步骤都保留，拼成执行过程时间线；`RESULT` 之前服务端会把仍在进行的步骤全部结束。
- 模型步骤不含模型名；推理轮次不显示上限。
- 同一时间线也通过历史接口返回（第 9 节），刷新后可还原。

### 4.2 TITLE：会话标题

```json
{"type":"TITLE","conversationId":"6e96…","traceId":"…","text":"我们有哪些标签？"}
```

- 只在标题生成或变化时下发；目前仅在本轮新建会话时开局发一次（标题取首句前 64 字），已有会话的轮次不发。
- 前端收到后把该会话插到会话列表顶部（或更新已有项的标题），并同步页面标题；无需再拉会话列表。

### 4.3 RESULT：本轮结果

```json
{
  "type": "RESULT",
  "conversationId": "6e96…",
  "status": "WAITING",
  "text": "确认为《G2rain 2.0 正式发布》添加标签吗？\n文章 ID：101，标签 ID：7",
  "actions": ["CONFIRM", "REJECT", "CANCEL"]
}
```

- 每轮恰好一个，且是最后一个事件。收到后即可结束本轮。
- `text` 是数字员工的回复，可能包含换行（`\n`），按纯文本渲染。
- `text`、`createdAtMs`、`elapsedMs` 与历史消息中的记录一致，刷新后显示相同内容。
- 等待确认这类多次请求的回合，`elapsedMs` 只计算本次请求，不累加用户操作的时间。

---

## 5. 结果状态与界面行为

| status | 含义 | 界面行为 |
|---|---|---|
| `DONE` | 本轮完成 | 显示回复；输入框可用 |
| `WAITING` | 等待用户操作 | 显示回复；按 `actions` 渲染按钮或输入方式（见第 6 节） |
| `FAILED` | 本轮失败 | 显示回复（已是对用户友好的说明）；可提供"重试"，即重新发送上一条输入 |
| `CANCELLED` | 已取消 | 显示回复；输入框可用 |

---

## 6. 动作与候选答案

### 6.1 actions

| 值 | 按钮文字建议 | 点击后发送 |
|---|---|---|
| `CONFIRM` | 确认 | `{"conversationId": "…", "action": "CONFIRM"}` |
| `REJECT` | 拒绝 / 没有生效 | `{"conversationId": "…", "action": "REJECT"}` |
| `CANCEL` | 取消 | `{"conversationId": "…", "action": "CANCEL"}` |
| `REPLY` | 不渲染为按钮，表示需要用户回答 | 用户输入后发送 `{"conversationId": "…", "userInput": "…", "action": "REPLY"}` |

### 6.2 三种等待场景

根据 `actions` 的组合区分，前端不需要额外字段：

| actions | 场景 | 界面 |
|---|---|---|
| `["REPLY", "CANCEL"]` | 追问：需要用户补充信息 | 输入框可用（回答时带 `action: REPLY`）；显示"取消"按钮；有 `suggestions` 时显示为可点选的选项 |
| `["CONFIRM", "REJECT", "CANCEL"]` | 审批：执行写操作前请用户确认，`text` 中列出将要执行的参数 | 显示"确认""拒绝""取消"按钮 |
| `["CONFIRM", "REJECT"]` | 核实：上次写操作结果不确定，请用户确认是否已生效 | 显示"已生效""未生效"按钮 |

按钮文字可以按场景调整，发送的 `action` 值不变。

### 6.3 suggestions

候选答案，常见于追问，例如"找到 2 篇同名文章，要操作哪一篇？"。

- 显示为可点击的选项。
- 点击后发送：`{"conversationId": "…", "userInput": "<选项文字>", "action": "REPLY"}`。
- 用户也可以不点选，直接输入回答。

### 6.4 按钮的有效期

- 只有**最近一条** `RESULT` 中的 `actions` 有效。
- 收到新的 `RESULT` 后，之前的按钮全部置为不可用。
- 用户在等待状态下直接输入文字（不带 `action`）也是允许的：服务端会判断是回答当前问题、还是开始新的事情。

---

## 7. 场景示例

以下示例省略 `conversationId`。

### 7.1 简单问答

请求：`{"channel":"web","userInput":"你好"}`

```text
event: PROGRESS
data: {"type":"PROGRESS","step":{"stepId":"s1","stage":"ROUTE","status":"RUNNING","title":"理解问题"}}

event: PROGRESS
data: {"type":"PROGRESS","step":{"stepId":"s1","stage":"ROUTE","status":"DONE","title":"理解问题","detail":"直接回复","elapsedMs":540}}

event: RESULT
data: {"type":"RESULT","status":"DONE","text":"你好，我是 CMS 内容管理数字员工，可以帮你管理网站、文章、页面和标签。"}
```

### 7.2 查询

请求：`{"channel":"web","userInput":"我们有哪些标签？"}`

以下 PROGRESS 只列结束帧（stage、status、title、detail），缩进表示层级；开始帧字段相同、`status` 为 `RUNNING`：

```text
ROUTE  DONE  理解问题            新开任务，选中「CMS 助手」
PLAN   DONE  规划步骤            规划 1 个节点：标签与文章打标
NODE   ...   标签与文章打标
ROUND  DONE    第 1 轮
MODEL  DONE      推理            1,203 tokens
TOOL   DONE      调用 查询标签   成功
ROUND  DONE    第 2 轮
MODEL  DONE      推理            860 tokens
NODE   DONE  标签与文章打标
```

```text
event: RESULT
data: {"type":"RESULT","status":"DONE","text":"目前共有 3 个标签：产品动态、技术分享、公司新闻。"}
```

### 7.3 追问与选项

请求：`{"channel":"web","userInput":"给《发布公告》打上产品动态标签"}`

```text
event: RESULT
data: {"type":"RESULT","status":"WAITING","text":"找到 2 篇标题为《发布公告》的文章，要操作哪一篇？","actions":["REPLY","CANCEL"],"suggestions":["2026-09-01 发布的","2026-10-01 发布的"]}
```

用户点击第二个选项：

```json
{"channel":"web","conversationId":"…","userInput":"2026-10-01 发布的","action":"REPLY"}
```

### 7.4 审批：确认

接上例，服务端准备执行写操作：

```text
event: RESULT
data: {"type":"RESULT","status":"WAITING","text":"确认为这篇文章添加标签吗？\n文章 ID：101，标签 ID：7","actions":["CONFIRM","REJECT","CANCEL"]}
```

用户点击"确认"：

```json
{"channel":"web","conversationId":"…","action":"CONFIRM"}
```

```text
event: PROGRESS
data: {"type":"PROGRESS","step":{"stepId":"t3","parentStepId":"t2","stage":"TOOL","status":"RUNNING","title":"调用 为文章添加标签"}}

event: PROGRESS
data: {"type":"PROGRESS","step":{"stepId":"t3","parentStepId":"t2","stage":"TOOL","status":"DONE","title":"调用 为文章添加标签","detail":"成功","elapsedMs":410}}

event: RESULT
data: {"type":"RESULT","status":"DONE","text":"已为《发布公告》添加标签「产品动态」。"}
```

### 7.5 审批：拒绝

```json
{"channel":"web","conversationId":"…","action":"REJECT"}
```

```text
event: RESULT
data: {"type":"RESULT","status":"FAILED","text":"已按你的要求放弃本次操作。"}
```

（`text` 以服务端实际回复为准。）

### 7.6 核实

上一次写操作超时，结果不确定：

```text
event: RESULT
data: {"type":"RESULT","status":"WAITING","text":"上次的写操作结果不确定，请确认是否已经完成。","actions":["CONFIRM","REJECT"]}
```

- 点击"已生效"：`{"action":"CONFIRM"}`，服务端视为已完成，不会重复执行。
- 点击"未生效"：`{"action":"REJECT"}`，服务端视为未完成，可以重新执行。

### 7.7 取消

```json
{"channel":"web","conversationId":"…","action":"CANCEL"}
```

```text
event: RESULT
data: {"type":"RESULT","status":"CANCELLED","text":"已取消。"}
```

### 7.8 没有待处理的操作

按钮已过期（例如重复点击）时：

```text
event: RESULT
data: {"type":"RESULT","status":"DONE","text":"当前没有待确认的操作。"}
```

### 7.9 失败

```text
event: RESULT
data: {"type":"RESULT","status":"FAILED","text":"处理没有成功，请稍后再试。"}
```

---

## 8. 前端实现

### 8.1 为什么不用 EventSource

浏览器原生 `EventSource` 只支持 GET，本接口是 POST，因此使用 `fetch` 读取流。

### 8.2 参考实现（TypeScript）

```ts
type TurnStatus = 'DONE' | 'WAITING' | 'FAILED' | 'CANCELLED';
type ResumeAction = 'REPLY' | 'CONFIRM' | 'REJECT' | 'CANCEL';

interface TurnStep {
  stepId: string;
  parentStepId?: string;
  stage: 'ROUTE' | 'PLAN' | 'NODE' | 'ROUND' | 'MODEL' | 'TOOL' | 'VERIFY';
  status: 'RUNNING' | 'DONE' | 'FAILED';
  title: string;
  detail?: string;
  nodeId?: string;
  round?: number;
  elapsedMs?: number;
  errorCode?: string;
}

interface TurnEvent {
  type: 'PROGRESS' | 'TITLE' | 'RESULT';
  conversationId: string;
  traceId: string;
  step?: TurnStep;
  text?: string;
  status?: TurnStatus;
  actions?: ResumeAction[];
  suggestions?: string[];
  createdAtMs?: number;
  elapsedMs?: number;
  errorCode?: string;
}

interface TurnRequest {
  channel: string;
  conversationId?: string;
  agentId?: number;
  userInput?: string;
  action?: ResumeAction;
}

export async function sendTurn(
  req: TurnRequest,
  onProgress: (e: TurnEvent) => void,
  onTitle: (e: TurnEvent) => void,
  onResult: (e: TurnEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const resp = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
    body: JSON.stringify(req),
    signal,
  });
  if (!resp.ok || !resp.body) {
    throw new Error(`HTTP ${resp.status}`);
  }

  const reader = resp.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  let gotResult = false;

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += value;

    let sep: number;
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);

      // 只取 data 行；注释行（: ping）和 event 行忽略
      const data = block
        .split('\n')
        .filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart())
        .join('\n');
      if (!data) continue;

      const event: TurnEvent = JSON.parse(data);
      if (event.type === 'PROGRESS') {
        onProgress(event);
      } else if (event.type === 'TITLE') {
        onTitle(event);
      } else if (event.type === 'RESULT') {
        gotResult = true;
        onResult(event);
      }
    }
  }

  if (!gotResult) {
    throw new Error('stream closed before RESULT');
  }
}
```

### 8.3 界面状态

```text
            发送请求
  空闲 ───────────────▶ 处理中（显示执行过程）
   ▲                       │ PROGRESS：按 stepId 追加或更新步骤
   │                       │ RESULT：
   │                       ▼
   │          ┌─ DONE / CANCELLED ──▶ 显示回复 ──▶ 空闲
   └──────────┼─ FAILED ────────────▶ 显示回复 + 重试 ──▶ 空闲
              └─ WAITING ───────────▶ 显示回复 + 按钮/选项 ──▶ 等待用户（可输入）
```

渲染要点：

| 事件 | 处理 |
|---|---|
| 发送前 | 把用户输入作为本地消息先显示出来 |
| 第一个事件 | 保存 `conversationId`（新会话） |
| `PROGRESS` | 按 `stepId` 合并步骤，按 `parentStepId` 缩进 |
| `RESULT` | 执行过程定格；追加回复气泡；`WAITING` 时按第 6 节渲染按钮、选项；禁用之前的按钮 |
| 点击按钮 | 立即禁用该组按钮，防止重复提交 |

### 8.4 并发

同一会话同一时间只能处理一个请求。前端在收到 `RESULT` 之前不应发送下一条请求（输入框可保持可编辑，但发送按钮置灰）。

---

## 9. 历史消息

打开已有会话或刷新页面时，通过历史接口加载消息：

```text
GET /chat/message/list?conversationId=<会话ID>
```

返回 `chat_message` 记录，前端按以下规则展示：

| 规则 | 说明 |
|---|---|
| 排序 | 按 `createdAtMs` 升序 |
| 用户消息 | `role = USER` 的全部显示 |
| 数字员工消息 | `role = AGENT` 且 `nodeId` 为空的才显示（带 `nodeId` 的是执行过程中的内部文字） |
| 正文 | `content` 字段 |
| 时间 | `createdAtMs` 字段 |
| 耗时 | `elapsedMs` 字段；仅最终回复有值，旧数据为空 |
| 执行过程 | `progress` 字段：本轮步骤列表（字段同 4.1，按开始顺序）；仅最终回复有值，旧数据为空 |

历史消息中不包含按钮。如果需要在刷新后恢复最后一轮的按钮，前端可以按会话缓存最近一条 `RESULT`；用户也可以直接输入文字继续对话。

---

## 10. 异常与边界

| 情况 | 表现 | 前端处理 |
|---|---|---|
| 请求参数不合法（如缺少 `channel`） | HTTP 400，没有事件流 | 提示参数错误 |
| 同一会话已有请求在处理 | 返回 `RESULT`，`status=FAILED` | 提示稍后再试 |
| 服务端处理异常 | 返回 `RESULT`，`status=FAILED`，`text` 按失败原因给出说明（见第 11 节），带 `errorCode` | 显示回复与追踪号，提供重试 |
| 连接在 `RESULT` 前断开 | 流结束但没有 `RESULT` | 重新加载历史消息（第 9 节）；服务端的处理可能已完成 |
| 长时间没有事件 | 每 15 秒收到一行 `: ping` | 无需处理；如需超时，建议不低于 120 秒 |
| 按钮已失效 | 返回"当前没有待确认的操作"或"当前不能执行这个操作" | 显示回复 |

服务端不支持断线续传，不读取 `Last-Event-ID`。

---

## 11. 文案参考

以下是服务端固定文案，可用于前端测试与对照（实际以返回为准）：

| 场景 | 文案 |
|---|---|
| 理解问题（首个步骤） | 理解问题 |
| 准备前置信息 | 正在准备所需信息 |
| 通用失败 | 处理没有成功，请稍后再试。 |
| 模型额度不足 | 模型服务额度不足，请联系管理员。 |
| 模型密钥无效 | 模型访问密钥无效，请联系管理员。 |
| 内容或参数不符合模型要求 | 内容过长或参数不符合模型要求，请精简后再试。 |
| 模型未配置 | 模型未配置或未启用，请联系管理员。 |
| 模型限流 | 模型繁忙，请稍后重试。 |
| 模型超时 | 模型响应超时，请稍后重试。 |
| 模型返回异常 | 模型服务返回异常，请稍后重试或联系管理员。 |
| 已取消 | 已取消。 |
| 已完成 | 已完成。 |
| 需要补充信息 | 还需要您补充一些信息。 |
| 没有待确认的操作 | 当前没有待确认的操作。 |
| 动作不允许 | 当前不能执行这个操作，请按页面上的选项操作。 |
| 缺少动作 | 请先在页面上选择要执行的操作。 |
| 任务已结束 | 之前的事项已结束或已过期，请重新发起。 |
| 写结果待核实 | 上次的写操作结果不确定，请确认是否已经完成。 |
| 找不到数字员工 | 找不到指定的数字员工。 |
| 数字员工未启用 | 该数字员工暂未启用。 |
| 没有合适的数字员工 | 暂时没有合适的数字员工处理这件事，您可以换个说法或指定数字员工。 |
| 待确认操作收到文字输入 | 还有一项操作等待您确认，请确认是否继续执行。 |
| 无法规划 | 这件事暂时处理不了，请换个说法或稍后再试。 |
