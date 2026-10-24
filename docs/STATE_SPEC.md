# g2rain-agent-runtime State 规范

本文档描述 Runtime 在一次对话中保存哪些状态、存在哪里、何时读写、如何恢复。内容以当前代码实现为准，配套图见 [runtime-state-model.svg](STATE_MODEL.svg)。

---

## 目录

1. [基本概念](#1-基本概念)
2. [存储布局](#2-存储布局)
3. [领域模型](#3-领域模型)
4. [一轮对话的生命周期](#4-一轮对话的生命周期)
5. [关键机制](#5-关键机制)
6. [配置项](#6-配置项)
7. [错误码](#7-错误码)
8. [实现约束与注意事项](#8-实现约束与注意事项)

---

## 1. 基本概念

### 1.1 三层状态

| 层 | 生命周期 | 内容 | 代码入口 |
|---|---|---|---|
| **会话（Conversation）** | 跨多轮、跨多个任务 | 会话栅栏、对话消息、已结束任务的记录、实体索引、会话级共享值 | `TurnScope`（`DefaultTurnScope`） |
| **任务（Task）** | 从开任务到终态 | 任务元数据、任务图、断点、任务级共享值、前情、写证据索引 | `TurnScope` 上的 task / graph / checkpoint |
| **节点（Node）** | 图中一个节点的执行 | 输入、产出、便签、工具证据、工具原始报文 | `NodeScope`（`DefaultNodeScope`） |

一轮请求称为 **Turn**，用 `traceId` 标识。`TurnScope` 是本轮内存中的状态视图，轮末统一写回存储。

### 1.2 存储分工

| 存储 | 角色 | 说明 |
|---|---|---|
| **Redis** | 热态，读写以它为准 | 每轮开始从 Redis 读状态；轮中通过 `flush.lua` 原子写入 |
| **MySQL** | 冷存储与兜底 | 轮末 `persist` 统一写入；Redis 中的 key 缺失或过期时从 MySQL 回灌 |

两个原则：

1. **Redis 为准**：同一轮里，任何状态变化先写 Redis（由版本栅栏保护），再落 MySQL。
2. **MySQL 兜底**：Redis 失效后，用 MySQL 恢复对话、任务、断点和写证据，保证可以继续对话、不重复执行写操作。

### 1.3 写入点

| 写入点 | 时机 | 写什么 |
|---|---|---|
| **S1** | 本轮用户消息进入时 | 用户消息：先 Redis，再同步写 MySQL `chat_message` |
| **S2** | 写工具调用前（预登记） | 一条 `STARTED` 证据：先 Redis，再同步写 MySQL `tool_call_record` |
| **S3** | 工具返回后（结算） | 证据终态、原始报文：先 Redis，再写 MySQL |
| **flush** | 轮中多次、轮末一次 | 本轮全部内存状态原子写入 Redis |
| **persist** | 轮末一次 | 本轮全部状态写入 MySQL（单事务） |

### 1.4 术语

| 术语 | 含义 |
|---|---|
| 栅栏（fence） | 会话状态中的 `version`。每轮开始时加 1；本轮所有 Redis 写入都必须携带这个版本，版本不一致即拒绝 |
| 断点（checkpoint） | 任务挂起等用户的那一刻保存的现场：问题、允许的动作、待执行的写操作 |
| 证据（evidence） | 一次业务工具调用的记录：状态、是否业务成功、是否空结果、摘要等 |
| 写证据索引（writes） | 按"工具名 + 幂等键"索引写操作的证据，用于防止重复写 |
| 发起节点 / 前置节点 | 委派关系中，调用 `builtin_node_delegate` 的节点是发起节点，被插入的子节点是前置节点 |
| 前情（prior） | 开新任务时，从已结束任务中带过来的摘要和实体 |

---

## 2. 存储布局

### 2.1 Redis Key

所有会话相关 key 使用 hash tag `{organId:conversationId}`，保证同一会话的 key 落在同一个 Redis slot，Lua 脚本可以原子操作。下表中 `{c}` 表示 `{organId:conversationId}`，`<tid>` 为任务 ID，`<nid>` 为节点 ID。

**会话层**

| Key | 类型 | 内容 | TTL |
|---|---|---|---|
| `g2rain:rt:{c}:state` | Hash | 会话栅栏：`userId`、`activeTaskId`、`version`、`lastTraceId`、`updatedAt` | `state.conversation-ttl`（默认 7 天），每轮续期 |
| `g2rain:rt:{c}:dialogue` | List | 对话消息 JSON，表头为最新；保留最近 `state.dialogue-max` 条 | 不设置 |
| `g2rain:rt:{c}:records` | List | 已结束任务的 `TaskRecord` JSON；保留最近 `state.records-max` 条 | 不设置 |
| `g2rain:rt:{c}:entities` | Hash | 实体索引：field 为 `type:id`，value 为 `EntityEntry` JSON | 不设置 |
| `g2rain:rt:{c}:shared` | Hash | 会话级共享值：field 为键名，value 为 `Sourced` JSON | 会话 TTL，每轮续期 |

**任务层**（任务进行中存在，终态落库成功后删除）

| Key | 类型 | 内容 |
|---|---|---|
| `g2rain:rt:{c}:task:<tid>:metadata` | Hash | 任务元数据（`status`、`currentNodeId`、`agentId`、`goalSummary`、`request`、`finalMessage`、`suspendedAt`）。同时作为任务组的"哨兵" |
| `g2rain:rt:{c}:task:<tid>:graph` | Hash | 任务图：field 为 nodeId，value 为 `TaskNode` JSON；另有 field `_edges` 保存全部边 |
| `g2rain:rt:{c}:task:<tid>:checkpoint` | String | 当前断点 `TaskCheckpoint` JSON；没有断点时删除 |
| `g2rain:rt:{c}:task:<tid>:shared` | Hash | 任务级共享值：键名 → `Sourced` JSON |
| `g2rain:rt:{c}:task:<tid>:prior` | String | 前情 `Prior` JSON |
| `g2rain:rt:{c}:task:<tid>:writes` | Hash | 写证据索引：field 为 `toolName\|idempotencyKey`，value 为 `{evidenceId, status, businessOk}` |

**节点层**

| Key | 类型 | 内容 |
|---|---|---|
| `g2rain:rt:{c}:task:<tid>:node:<nid>:space` | Hash | 节点空间：`inputs`、`outputs`、`state`、`rounds`、`tokens`、`actions`。单个字段过大时换成溢出占位（见 5.10） |
| `g2rain:rt:{c}:task:<tid>:node:<nid>:evidence` | Hash | 证据头：field 为 evidenceId，value 为 `EvidenceHead` JSON |
| `g2rain:rt:{c}:task:<tid>:payload:<eid>` | String | 工具原始报文 JSON |

**任务组 TTL**

| 场景 | TTL |
|---|---|
| 任务进行中 | `state.task-ttl-running`（默认 24 小时） |
| 任务挂起（SUSPENDED） | `state.task-ttl-suspended`（默认 2 小时） |
| `metadata`（哨兵）在续期时 | 任务 TTL 减去 `state.sentinel-gap`（默认 10 分钟） |
| 节点 DONE 后的 `payload` | `state.payload-done-ttl`（默认 1 小时；配置为 0 表示立即删除）。仍有下游节点未结束的上游节点报文除外（下游会直接读上游证据） |

**其他 key**

| Key | 用途 |
|---|---|
| `g2rain:rt:lock:conv:{cid}` | 会话锁，同一会话同一时刻只处理一个请求，拿不到锁立即失败 |

### 2.2 Lua 脚本

#### renew.lua（每轮开始执行一次）

| 参数 | 含义 |
|---|---|
| `KEYS[1]` | 会话 `state` key |
| `ARGV[1]` | 本轮 traceId |
| `ARGV[2]` | 会话 TTL（毫秒） |
| `ARGV[3]` | 会话共享 key，可为空串 |
| `ARGV[4]` | 基线版本（Redis 已有值，或 Redis 缺失时从 MySQL 读回的 `state_version`） |
| `ARGV[5..]` | 任务组 key 与 TTL，两两成对 |

执行步骤：

1. 读出旧的 `lastTraceId`。
2. `version` 不存在时，先写入基线版本。
3. `version` 加 1，作为本轮栅栏。
4. `lastTraceId` 写为本轮 traceId，`updatedAt` 写为当前时间。
5. 续期会话 `state`、会话共享 key，以及全部任务组 key。
6. 返回 `{version, 旧的 lastTraceId}`。

旧的 `lastTraceId` 非空，说明上一轮没有正常收尾（见 5.2）。

#### flush.lua（轮中、轮末写 Redis）

| 参数 | 含义 |
|---|---|
| `KEYS[1]` | 会话 `state` key |
| `ARGV[1]` | 本轮栅栏版本 |
| `ARGV[2]` | 改动列表 JSON：`[{op, key, field, value, ttl}]` |

执行步骤：

1. 读出 `state.version`，与 `ARGV[1]` 不相等则直接返回 0，不做任何写入。
2. 依次执行改动列表，返回 1。

| `op` | 动作 |
|---|---|
| `HSET` | 写 Hash 字段；带 `ttl` 时同时 `PEXPIRE` |
| `HDEL` | 删除 Hash 字段（字段值为 null 时生成） |
| `SET` | 写 String；带 `ttl` 时使用 `PX` |
| `DEL` | 删除 key |
| `LPUSH_TRIM` | 头部插入，再裁剪到 `ttl` 条（此处 `ttl` 表示保留条数） |
| `LREM` | 从 List 删除指定值 |
| `EXPIRE` | 续期（毫秒） |

返回 0 时 Runtime 抛出 `STATE_FENCED`，本轮放弃写入。

### 2.3 MySQL 表

所有表都带公共列 `id`、`organ_id`、`create_time`、`update_time`、`version`、`delete_flag`。租户条件由 MyBatis 拦截器自动追加。

| 表 | 用途 | 写入 | 读回 |
|---|---|---|---|
| `chat_conversation` | 会话：`conversation_id`、`user_id`、`title`、`active_task_id`、`session_shared`、`state_version`、`last_message_time` | 首轮建行；轮末按 `state_version` CAS 更新 | Redis `state` 缺失时回灌栅栏、活动任务和会话共享 |
| `chat_message` | 对话消息：`message_id`、`role`、`message_type`、`content`、`task_id`、`node_id`、`round`、`trace_id`、`created_at_ms` | 用户消息在 S1 写；其他消息在轮末写 | Redis `dialogue` 为空时回灌；历史消息接口读取 |
| `task_instance` | 任务：`task_id`、`agent_id`、`goal_summary`、`request`、`status`、`current_node_id`、`final_message`、`shared`、`prior`、`record`、`state_version`、`started_time`（任务开启时刻）、`suspended_time`、`finished_time`、`trace_id`（开启任务那一轮）、`source_message_id`（触发开启的用户消息，入库后回填该消息的 `task_id`） | 轮末写；已存在时按 `state_version` CAS 更新 | 任务组 key 缺失时回灌；`record` 用于回灌 records |
| `task_graph_node` | 节点：`node_id`、`title`、`objective`、`skill_key`、`target`（完成目标）、`target_claimed`、`delegate_depth`、`status`、`result`（节点结论）、`inputs`、`outputs`、`state`（完整节点空间 JSON）、`error_code`、`error_message`、`started_time`（首次运行）、`finished_time`（进入终态） | 轮末写 | 任务图、节点空间回灌 |
| `task_graph_edge` | 边：`from_node_id`、`to_node_id`、`edge_type` | 轮末追加新边 | 任务图回灌 |
| `task_checkpoint` | 断点：`checkpoint_id`、`node_id`、`type`、`question`、`expected_fields`、`allowed_actions`、`suggestions`、`pending_action` | 轮末写；断点被消费时逻辑删除（`delete_flag=1`） | 断点回灌 |
| `task_resume_record` | 断点恢复记录：`resume_id`、`checkpoint_id`、`message_id`、`resume_action`、`acted_at` | 断点被消费时插入 | 审计用 |
| `tool_call_record` | 工具调用：`call_id`（即 evidenceId）、`tool_name`、`side_effect`、`request`、`args_hash`、`idempotency_key`、`round`、`response`、`result_summary`、`entities`、`payload_id`、`call_status`、`business_ok`、`empty_result`、`failure_kind`、`error_code`、`error_message`、`duration_ms` | 写工具在 S2 插入、S3 更新；读工具在结算时插入；轮末补写证据状态 | 证据回灌、写操作去重 |
| `task_node_payload` | 大对象：`payload_id`、`payload_type`（`ARTIFACT` 报文 / `OUTPUT` 节点空间字段）、`payload_data` | 报文或节点空间字段超过阈值时写 | 读取超大报文或溢出字段 |

---

## 3. 领域模型

### 3.1 会话层

#### ConversationFence（会话栅栏）

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversationId` | String | 会话 ID（不写入 Redis Hash） |
| `userId` | String | 会话所属用户；与当前请求用户不一致时拒绝访问 |
| `activeTaskId` | String? | 当前活动任务；无任务时为空 |
| `version` | long | 栅栏版本 |
| `lastTraceId` | String? | 正在处理的轮次；正常收尾后清空 |
| `updatedAt` | long | 最近更新时间（毫秒） |

#### DialogueMessage（对话消息）

| 字段 | 类型 | 说明 |
|---|---|---|
| `messageId` | String | 消息 ID |
| `role` | DialogueRole | 发言方 |
| `type` | MessageType | 消息类型 |
| `text` | String | 正文 |
| `traceId` | String | 所属轮次 |
| `taskId` | String? | 所属任务 |
| `nodeId` | String? | 所属节点。模型的中间文字、以及回答追问的用户消息会带上节点 ID |
| `round` | Integer? | 节点内的 ReAct 轮次 |
| `createdAt` | long | 创建时间（毫秒） |

| DialogueRole | 含义 |
|---|---|
| `USER` | 终端用户 |
| `AGENT` | 数字员工 |

| MessageType | 含义 |
|---|---|
| `USER_TEXT` | 用户文本 |
| `AGENT_REPLY` | 数字员工的回复，或节点内的中间文字 |

对话窗口的规则：

- 会话级对话（用于意图判定、来源校验）排除"`AGENT` 且带 `nodeId`"的中间文字。
- 节点内历史只取 `nodeId` 等于该节点的消息，按 `round` 排序，并与该节点的证据交织组装。

#### TaskRecord（已结束任务记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | String | 任务 ID |
| `agentId` | String | 执行的数字员工 |
| `goal` | String? | 任务目标摘要 |
| `status` | TaskStatus | 终态 |
| `summary` | String? | 收口文案 |
| `entities` | Map<String, EntityEntry> | 结束时的实体索引 |
| `artifacts` | List | 产物引用（当前为空） |
| `closedAt` | long | 结束时间 |

#### EntityEntry（实体）

实体是从工具结果或节点产出中识别出的业务对象（如文章、标签），用于后续任务引用。索引键为 `type:id`。

| 字段 | 类型 | 说明 |
|---|---|---|
| `displayName` | String | 展示名 |
| `attrs` | JSON | 标量属性 |
| `taskId` | String? | 来源任务 |
| `evidenceId` | String? | 来源证据 |
| `seenAt` | long | 最近一次出现时间 |
| `mentions` | int | 出现次数 |

#### 共享值：Sourced / Source / SourceType

共享值、节点输入等都以"带来源的值"保存，以便判断一个值是否可信。

| 类型 | 结构 |
|---|---|
| `Sourced<T>` | `{value, source}` |
| `Source` | `{type: SourceType, ref, path}` |

| SourceType | 含义 | `ref` / `path` |
|---|---|---|
| `USER` | 用户明确提供 | 用户消息 ID |
| `EVIDENCE` | 工具证据 | evidenceId / 报文路径 |
| `NODE_OUTPUT` | 上游节点产出 | nodeId / 字段名 |
| `SHARED` | 任务级共享值 | 键名 |
| `SESSION` | 会话级共享值 | 键名 |
| `PRIOR_TASK` | 前情 | 实体键 |
| `MODEL` | 大模型生成 | — |
| `CONTRACT` | 契约常量 | — |
| `PLATFORM` | 平台注入（登录身份等） | 字段名 |

### 3.2 任务层

#### TaskMetadata

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | String | 任务 ID |
| `conversationId` | String | 所属会话 |
| `status` | TaskStatus | 任务状态 |
| `currentNodeId` | String? | 当前焦点节点 |
| `agentId` | Long? | 执行的数字员工 |
| `goalSummary` | String? | 任务目标摘要 |
| `request` | String? | 开任务时的用户原文；节点提示词的 `userGoal` 取它，摘要会丢细节 |
| `finalMessage` | String? | 终态文案 |
| `suspendedAt` | Long? | 挂起时间 |

| TaskStatus | 含义 | 是否终态 |
|---|---|---|
| `RUNNING` | 运行中 | 否 |
| `SUSPENDED` | 挂起，等用户 | 否 |
| `SUCCEEDED` | 成功结束 | 是 |
| `FAILED` | 失败结束 | 是 |
| `CANCELED` | 已取消 | 是 |

#### TaskGraph / TaskNode / TaskEdge

| TaskNode 字段 | 类型 | 说明 |
|---|---|---|
| `nodeId` | String | 节点 ID |
| `title` | String? | 节点标题，用作过程提示 |
| `objective` | String? | 节点目标 |
| `skillKey` | String? | 执行的技能 |
| `target` | String? | 完成目标：要完成的写工具；取值顺序为规划输出 → 契约 `target` → 首个实际写调用，见契约规范第 10 节 |
| `targetClaimed` | boolean | 完成目标是否由首个写调用自动记录；为 true 时 GoalGate 不直接采用节点结论，交模型核验 |
| `result` | String? | 节点结论：完成时 finish 的 output，GoalGate 据此核验目标并合成答复 |
| `status` | NodeStatus | 节点状态，默认 `PENDING` |
| `dependsOn` | List<String> | 依赖的节点 |
| `errorCode` / `errorMessage` | String? | 失败信息 |
| `delegateDepth` | int | 委派深度，规划出的节点为 0 |

| TaskEdge 字段 | 说明 |
|---|---|
| `from` | 上游节点 |
| `to` | 下游节点 |
| `type` | EdgeType |

| EdgeType | 含义 |
|---|---|
| `NORMAL` | 普通依赖：上游 `DONE` 或 `SKIPPED` 后下游才能执行 |
| `DELEGATE` | 委派：前置节点 → 发起节点。前置节点到达任意终态后，发起节点恢复执行 |

| NodeStatus | 含义 | 可调度 | 放开下游 | 终态 |
|---|---|---|---|---|
| `PENDING` | 等上游 | 是 | 否 | 否 |
| `READY` | 可执行 | 是 | 否 | 否 |
| `RUNNING` | 执行中 | 否 | 否 | 否 |
| `WAITING_DELEGATE` | 等前置节点 | 否 | 否 | 否 |
| `SUSPENDED` | 等用户 | 否 | 否 | 否 |
| `DONE` | 完成 | 否 | 是 | 是 |
| `SKIPPED` | 跳过（所需信息已存在） | 否 | 是 | 是 |
| `FAILED` | 失败 | 否 | 否 | 是 |
| `CANCELLED` | 取消 | 否 | 否 | 是 |

节点状态迁移见 5.11。

#### TaskCheckpoint（断点）

| 字段 | 类型 | 说明 |
|---|---|---|
| `checkpointId` | String | 断点 ID |
| `type` | CheckpointType | 断点类型 |
| `question` | String? | 展示给用户的问题。审批时由 Runtime 拼上待执行参数 |
| `allowedActions` | List<ResumeAction> | 允许的恢复动作，默认取类型的默认动作 |
| `suggestions` | List<String> | 候选答案 |
| `pendingAction` | PendingAction? | 审批通过后要执行的写操作 |
| `expectedFields` | List<String> | 追问时要补的参数路径 |

| CheckpointType | 场景 | 默认动作 | 不带动作时的默认恢复 |
|---|---|---|---|
| `FOLLOW_UP` | 追问补充信息 | `REPLY`、`CANCEL` | `REPLY` |
| `APPROVAL` | 写操作前请用户确认 | `CONFIRM`、`REJECT`、`CANCEL` | 无（必须明确动作） |
| `VERIFY` | 写操作结果不确定，请用户核实是否已生效 | `CONFIRM`、`REJECT` | 无（必须明确动作） |

| ResumeAction | 含义 |
|---|---|
| `REPLY` | 文字回答，用于追问 |
| `CONFIRM` | 确认。审批时执行待批写操作；核实时表示"已生效" |
| `REJECT` | 拒绝。审批时放弃该写操作；核实时表示"未生效" |
| `CANCEL` | 放弃当前任务 |

| PendingAction 字段 | 说明 |
|---|---|
| `toolName` | 待执行的写工具 |
| `args` | 参数 JSON |
| `argsHash` | 参数摘要，执行前校验参数未被改动 |

#### Prior（前情）

| 字段 | 类型 | 说明 |
|---|---|---|
| `tasks` | List<TaskRecord> | 最近 `state.prior-tasks-max` 条已结束任务 |
| `entities` | Map<String, EntityEntry> | 最多 `state.prior-entities-max` 个实体 |
| `artifacts` | List | 产物引用（当前为空） |
| `reason` | String | 生成原因 |

### 3.3 节点层

#### NodeSpace（节点空间）

| 字段 | 类型 | 说明 |
|---|---|---|
| `inputs` | Map | 节点输入，开始执行时按契约 `inputs` 绑定，只补不覆盖 |
| `outputs` | Map | 节点正式产出，通过 `builtin_output_set` 写入 |
| `state` | Map | 节点便签，包含系统保留键（见下）和 `builtin_state_set` 写入的私有键 |
| `rounds` | int | 已执行的 ReAct 轮数 |
| `tokens` | long | token 累计（当前未统计，为 0） |
| `actions` | List<NodeAction> | 没有业务证据的动作：模型调用内置工具、调用执行前被拒或推迟、用户确认或拒绝挂起。保留最近 `state.node-actions-max` 条，说明按 `state.node-action-max-chars` 截断 |

**节点历史的跨轮重建**（`RuntimeStateFacade.history`）：按轮次合并三类来源，每轮依次为"用户的话与用户动作 → 模型的文字与工具调用 → 调用返回"。

| 来源 | 内容 |
|---|---|
| 对话消息（带本节点 `nodeId`） | 用户的话、模型的文字 |
| 证据头 | 业务工具调用；返回内容未成功时取 `detail`，成功时取完整报文，都没有才用 `resultBrief` |
| `actions` | 内置工具、被拒或推迟的调用及其返回；用户确认或拒绝 |

用户确认执行待批写操作、确认核实结果时，回应单独成一轮，排在派发结果与之后的模型调用之前。

| 保留便签键（NodeNoteKey） | 含义 |
|---|---|
| `awaiting` | 发起节点正在等待的前置节点 ID |
| `delegateResult` | 前置节点未成功时回给发起节点的结果，投影给模型一次后清除 |
| `entityOutputs` | 节点完成时产出中的实体候选 |

#### EvidenceHead（证据头）

| 字段 | 类型 | 说明 |
|---|---|---|
| `evidenceId` | String | 证据 ID，同时是 `tool_call_record.call_id` |
| `toolName` | String | 工具名 |
| `sideEffect` | SideEffect | 读/写 |
| `status` | EvidenceStatus | 调用状态 |
| `businessOk` | Boolean? | 业务是否成功（按契约 `success` 判定） |
| `empty` | Boolean? | 结果是否为空 |
| `extractCounts` | Map<String, Integer>? | 各抽取项条数 |
| `failureKind` | FailureKind | 失败分类 |
| `idempotencyKey` | String? | 幂等键 |
| `argsHash` | String? | 参数摘要 |
| `argsBrief` / `resultBrief` | String? | 脱敏后的参数、结果摘要 |
| `errorCode` | String? | 错误码 |
| `entities` | String? | 实体候选 JSON |
| `payloadId` | String? | 超大报文的存储指针 |
| `round` | int | 所在轮次 |
| `traceId` | String | 所在 Turn |
| `detail` | String? | 未成功（执行失败或业务失败）时返回给模型的完整说明"原因。下一步：…"，按 `react.observation-error-max-chars` 截断；落库到 `tool_call_record.error_message` |

| EvidenceStatus | 含义 |
|---|---|
| `STARTED` | 已预登记，工具尚未返回 |
| `SUCCESS` | 已返回（是否业务成功看 `businessOk`） |
| `FAILED` | 调用失败或被拦截 |
| `TIMEOUT` | 读工具超时 |
| `CANCELLED` | 未调用或已取消 |
| `UNKNOWN` | 写工具可能已执行，但结果不确定 |
| `UNKNOWN_PRE_CALL` | 预登记结果不确定，本次未调用 |

| FailureKind | 含义 |
|---|---|
| `NONE` | 无失败 |
| `AUTH_FAILURE` | 鉴权失败 |
| `PARAMETER_FAILURE` | 参数不合法 |
| `BUSINESS_FAILURE` | 业务拒绝 |
| `TRANSPORT_FAILURE` | 传输失败 |
| `RATE_LIMIT` | 限流 |
| `TIMEOUT` | 超时 |
| `UNKNOWN` | 无法归类 |

| SideEffect | 含义 |
|---|---|
| `NONE` | 只读工具 |
| `WRITE` | 写工具，调用前需确认、预登记，结果需结算 |

### 3.4 对外状态：TurnStatus

`TurnStatus` 是客户端唯一能看到的状态，随结果帧下发，由任务状态映射得到。

| TurnStatus | 含义 | 来源 |
|---|---|---|
| `DONE` | 本轮完成 | 任务 `SUCCEEDED`，或本轮只回复、未开任务 |
| `WAITING` | 等用户操作 | 任务 `SUSPENDED` |
| `FAILED` | 本轮失败 | 任务 `FAILED`；或收口时任务仍为 `RUNNING`；或状态缺失 |
| `CANCELLED` | 已取消 | 任务 `CANCELED` |

---

## 4. 一轮对话的生命周期

```text
请求 ─▶ ① 加锁 ─▶ ② begin ─▶ ③ 过程提示 ─▶ ④ 编排流水线 ─▶ ⑤ flush ─▶ ⑥ persist ─▶ ⑦ 结果帧 ─▶ ⑧ 解锁
```

### ① 加锁

- 会话 ID 为空时，由服务端生成。
- 对 `lock:conv:{cid}` 加锁。拿不到锁立即失败，客户端收到失败结果帧。

### ② begin：装载状态

| 步骤 | 动作 | 读写 |
|---|---|---|
| 2.1 | 读会话 `state`；Redis 中没有时，从 `chat_conversation` 回灌 `userId`、`activeTaskId`、`state_version` | Redis `state`、MySQL `chat_conversation` |
| 2.2 | 会话用户与当前用户不一致 → `CONVERSATION_FORBIDDEN` | — |
| 2.3 | MySQL 中没有该会话 → 新建会话行 | MySQL `chat_conversation` |
| 2.4 | 读会话层：对话、记录、会话共享（Redis 优先，缺失回灌 MySQL）；实体（只读 Redis） | Redis、MySQL |
| 2.5 | 有活动任务时，逐个读任务组：元数据、图、断点、任务共享、前情（Redis 优先，缺失回灌 MySQL） | Redis、MySQL |
| 2.6 | 执行 `renew.lua`：推进栅栏、续期、取回旧的 `lastTraceId` | Redis |
| 2.7 | 旧 `lastTraceId` 非空且任务在 `RUNNING` → 中断规整（见 5.2） | Redis |
| 2.8 | flush 一次，让规整结果落到 Redis | Redis |
| 2.9 | **S1**：用户输入非空时，追加用户消息（存在追问断点时，消息带上断点节点 ID 和轮次），flush，再同步写 `chat_message` | Redis、MySQL |

### ③ 过程提示

begin 完成后发送第一条过程提示（`PROGRESS`），客户端从这一帧拿到会话 ID。

### ④ 编排流水线

| 阶段 | 实现 | 对状态的影响 |
|---|---|---|
| 入口闸门 | `DefaultTurnEntryGuard` | 校验恢复动作；不合法时直接给出回复，不进入后续阶段 |
| 意图决策 | `DefaultTurnDispatcher` | 判定本轮意图（见下表） |
| 任务生命周期 | `DefaultTaskLifecycle` | 放弃旧任务（记恢复动作 `CANCEL`、任务置 `CANCELED`）；开新任务（生成前情） |
| 技能装载 | `DefaultHydrator` | 加载数字员工的技能与契约 |
| 规划 | `DefaultPlanner` | 生成任务图，设置焦点节点；续跑时沿用已有图 |
| 图执行 | `DefaultTaskGraphExecutor` | 逐节点执行：打开节点空间 → 绑定输入 → ReAct 循环 → 产生副作用（挂起、清除挂起、停图） |
| 收口 | `DefaultFinalizer` | 生成本轮回复并追加为 AGENT 消息；任务到终态时关闭任务 |

| 意图（TurnMode） | 含义 |
|---|---|
| `START_FRESH` | 新建任务，不继承前情 |
| `START_WITH_CONTEXT` | 新建任务，继承上一任务的前情 |
| `CONTINUE_TASK` | 在已有任务上继续，包括断点恢复 |
| `REPLACE_TASK` | 放弃当前任务，同一数字员工按新目标开任务 |
| `SWITCH_AGENT` | 放弃当前任务，换数字员工开任务 |
| `CANCEL_TASK` | 放弃当前任务，不开新任务 |
| `REPLY_ONLY` | 只回复，不推进任务 |
| `UNCERTAIN` | 无法判断意图，只澄清，不推进任务 |

节点执行期间的状态写入：

- ReAct 每轮调用模型前 flush 一次。
- 写工具调用前执行 **S2**，返回后执行 **S3**（见 5.4）。
- 读工具返回后直接登记一条证据并写 `tool_call_record`。

### ⑤ flush

把本轮内存状态整体写入 Redis，包括：会话栅栏、新增消息、实体、会话共享、任务组全部字段、本轮打开过的节点的空间与证据、写证据索引、新增报文。

| 情况 | 结果 |
|---|---|
| 改动超过 `state.flush-max-bytes` | `STATE_WRITE_FAILED` |
| Redis 异常 | `STATE_UNAVAILABLE` |
| 栅栏不一致 | `STATE_FENCED` |

### ⑥ persist

在一个事务内写 MySQL，顺序如下：

1. 以 CAS 推进 `chat_conversation.state_version`，同时写会话共享和活动任务。
2. 写被替换掉的旧任务（任务、图、节点空间、记录）。
3. 写当前任务：`task_instance`（CAS）、节点、边、证据状态；断点被消费时写恢复记录并逻辑删除断点；仍有断点时写断点。
4. 写本轮新增的非用户消息。

persist 成功后：

- 已结束的任务：在 Redis 中追加一条 `TaskRecord`，删除整个任务组，清空 `activeTaskId`。
- 再 flush 一次，清空 `lastTraceId`，标志本轮正常收尾。

persist 失败时重试一次；仍失败则记录错误日志，`lastTraceId` 保留，下一轮按中断处理。

### ⑦ 结果帧

发送 `RESULT`（见 [SSE_EVENTS.md](SSE_EVENTS.md)）。编排过程中发生异常时，会先把当前节点和任务标记为失败、追加失败回复，再发送失败结果帧。

### ⑧ 解锁

---

## 5. 关键机制

### 5.1 版本栅栏

| 环节 | 规则 |
|---|---|
| 推进 | 只有 `renew.lua` 在每轮开始时把 `version` 加 1 |
| Redis 校验 | 每次 `flush.lua` 都比较版本，不一致则拒绝写入 |
| MySQL 校验 | `chat_conversation`、`task_instance` 按 `state_version < 本轮版本` 条件更新，影响 0 行即 `STATE_FENCED` |
| Redis 失效后 | 以 MySQL 的 `state_version` 作为基线继续递增，版本不会回退 |

作用：同一会话如果出现过期的旧请求（例如超时后仍在执行），它的写入会被拒绝，不会覆盖新一轮的状态。

### 5.2 中断判定与规整

- 每轮开始时，`renew.lua` 返回上一轮写入的 `lastTraceId`；正常收尾会把它清空。
- 返回值非空，说明上一轮没有正常收尾（进程退出、落库失败等）。
- 此时若任务处于 `RUNNING`，执行中断规整：
  - `RUNNING` 的节点退回 `READY`，可以重新执行；
  - `STARTED` 的证据改为 `UNKNOWN`，后续按"写结果不确定"处理（见 5.5）。

### 5.3 断点

**写入**：节点挂起（追问、审批）或检测到不确定的写操作（核实）时，生成断点，任务置 `SUSPENDED`，焦点设为该节点。审批断点的 `question` 由 Runtime 拼上待执行参数（按"标签：值"格式，敏感字段打码，平台注入参数不显示）。

**恢复入口规则**（`DefaultTurnEntryGuard`）：

| 请求 | 无活动断点 | 有活动断点 |
|---|---|---|
| `action` 为 CONFIRM/REJECT/CANCEL | 回复"当前没有待确认的操作" | 动作在 `allowedActions` 内 → 恢复；否则回复"当前不能执行这个操作" |
| `action` 为 REPLY | 正常处理 | 恢复（作为追问的回答） |
| 无 `action`，有文字 | 正常处理 | 交给意图判定：继续当前任务，或换任务 |
| 无 `action`，无文字 | — | 按断点类型的默认动作恢复（只有追问有默认动作 `REPLY`）；否则回复"请先在页面上选择要执行的操作" |
| 请求的数字员工与任务不一致 | — | 换数字员工 |
| 审批断点挂起超过 `state.reconfirm-after` | — | 不直接恢复，交给意图判定重新确认 |

**恢复执行**：

| 断点 | 动作 | 结果 |
|---|---|---|
| FOLLOW_UP | REPLY | 唤醒节点；用户回答作为该节点的历史消息交给模型 |
| APPROVAL | CONFIRM | 校验参数摘要后执行待批写操作，再继续该节点 |
| APPROVAL | REJECT | 不执行待批写操作；节点 `CANCELLED`，任务 `CANCELED`，回复"已取消，未执行：{审批摘要}。如需换个做法，直接告诉我。" |
| VERIFY | CONFIRM | 不确定的证据改为 `SUCCESS`，不重复执行 |
| VERIFY | REJECT | 不确定的证据改为 `FAILED`，模型可以重新发起 |
| 任意 | CANCEL | 节点 `CANCELLED`，任务 `CANCELED` |

**消费**：节点被唤醒（或用户拒绝、取消而关闭挂起）时，断点即被消费：`turn.checkpoint()` 置空、任务回到 `RUNNING`，同时记下恢复动作。消费后的断点经 `turn.resumed()` 读取，确认续跑据此派发待批写操作，核实续跑据此改证据状态，追问回答据此认定参数。续跑中给模型的投影不再展示该断点。节点续跑后再次挂起时，新断点照常写入。persist 时先写一条 `task_resume_record` 并把旧 `task_checkpoint` 逻辑删除，再写入新断点（如有），避免 Redis 失效后被重新读回，也保证每次恢复都有记录。

### 5.4 证据与写操作去重

**幂等键**

| 契约 | 幂等键 |
|---|---|
| 配置了 `idempotencyKey` | 按路径字典序取参数值，拼成 `toolName\|path=value…`，取 SHA-256 |
| 未配置，或所列参数都缺失 | `sha256(toolName \| argsHash)` |

**S2 预登记**（`EvidenceFlow.prewrite`）

查重顺序：本节点证据 → Redis 写证据索引 → MySQL `tool_call_record`。

| 查到的证据 | 结果 | 说明 |
|---|---|---|
| `SUCCESS` 且业务成功 | `Reused` | 不再调用工具，直接返回上次的结果 |
| `STARTED` / `UNKNOWN` / `UNKNOWN_PRE_CALL` | `Blocked` | 拒绝调用，提示"进行中"或"待确认" |
| 没有，或上次失败 / 业务失败 | `Proceed` | 登记 `STARTED` 证据 → flush → 插入 `tool_call_record` → 调用工具 |

预登记写 MySQL 时：

- 暂时性异常 → 证据改为 `UNKNOWN_PRE_CALL`，本次不调用；
- 其他数据库异常 → 证据改为 `CANCELLED`，抛出 `STATE_WRITE_FAILED`。

**S3 结算**（`EvidenceFlow.settle`）：按工具结果更新证据状态、业务结果、空结果、抽取计数、实体；报文写入 Redis，超过 `state.payload-inline-max-bytes` 时另存 `task_node_payload`。

**调用异常到证据状态的映射**

| 错误 | 失败分类 | 读工具 | 写工具 |
|---|---|---|---|
| 超时 | `TIMEOUT` | `TIMEOUT` | `UNKNOWN` |
| 服务不可达 | `TRANSPORT_FAILURE` | `FAILED` | `FAILED` |
| IO 异常 | `TRANSPORT_FAILURE` | `FAILED` | `UNKNOWN` |
| 未知调用错误 | `UNKNOWN` | `FAILED` | `UNKNOWN` |
| MCP 工具错误 | `BUSINESS_FAILURE` | `FAILED` | `FAILED` |
| MCP 参数错误 | `PARAMETER_FAILURE` | `FAILED` | `FAILED` |
| MCP RPC 错误 | `UNKNOWN` | `FAILED` | `UNKNOWN` |
| 工具正常返回但业务失败 | `BUSINESS_FAILURE` | `SUCCESS` + `businessOk=false` | 同左 |

### 5.5 写结果不确定（VERIFY）

节点再次打开时，处理所有 `UNKNOWN` 的写证据：

1. 契约为该写工具配置了 `verify` → 调用回查工具：回查成功记为 `SUCCESS`，否则 `FAILED`。
2. 未配置回查 → 挂起 `VERIFY` 断点，询问用户"上次的写操作是否已完成"。
   - `CONFIRM` → `SUCCESS`；
   - `REJECT` → `FAILED`，允许模型重新发起。

结论出来之前，同一个写操作不能再次调用，节点也不能完成。

### 5.6 委派

| 环节 | 行为 |
|---|---|
| 发起 | 模型调用 `builtin_node_delegate(skillKey, objective)`。要求：技能在可委派目录中（契约 `delegatable=true`，且只读）；深度不超过 `state.delegate-max-depth`；同一发起节点下相同技能、相同目标的前置节点未曾失败 |
| 建图 | 新建 `READY` 的前置节点（深度 +1）；增加 `DELEGATE` 边；发起节点依赖它，记下 `awaiting`，进入 `WAITING_DELEGATE` |
| 恢复 | 前置节点到达任意终态后，发起节点回到 `READY` |
| 回填 | 发起节点再次打开时：前置节点 `DONE` → 正常继续；前置节点失败或取消 → 写入 `delegateResult`，投影给模型一次 |
| 失败隔离 | 前置节点失败不会让任务失败，由发起节点决定后续处理 |
| 数据传递 | 发起节点可以通过 `evidence.*` 读取前置节点的证据，节点完成目标（target）也认可前置节点的成功证据 |

### 5.7 参数被拒后的处理

工具调用被参数校验拦下时，拦截器只返回观测"原因。下一步：…"（追问、先调指定工具或失败），不在节点上挂额外约束：

- 模型按"下一步"处理后正常继续；
- 模型无视提示再次以不合规参数调用，会被同一拦截器再次拒绝，并计入无进展次数，超过 `react.max-no-progress-rounds` 后节点失败。

### 5.8 任务收口、记录与实体

| 环节 | 行为 |
|---|---|
| 关闭 | 任务到终态时只修改内存中的状态；`activeTaskId` 在 persist 成功后才清空 |
| 记录 | persist 成功后生成 `TaskRecord`，写入 `task_instance.task_record`，并追加到 Redis `records` |
| 实体 | persist 时从本轮成功证据的 `entities` 和已完成节点的 `entityOutputs` 合并实体索引；超过 `state.entities-max` 时淘汰最早加入的 |
| 换任务 | 开新任务时，旧任务打包为 `ClosedTask`，随本轮 persist 一起落库，再删除旧任务组 |

### 5.9 前情

| 项 | 规则 |
|---|---|
| 生成时机 | 开新任务时 |
| tasks | 最近 `state.prior-tasks-max` 条任务记录 |
| entities | 最多 `state.prior-entities-max` 个实体 |
| 使用方 | 节点输入绑定（`prior.<type>[.<attr>]`）、参数绑定、节点提示词、规划提示词 |

### 5.10 共享值与大对象

**共享值**

| | 任务级 | 会话级 |
|---|---|---|
| 写入方式 | 契约 `outputs.<k>.share = TASK`，节点完成时写入 | 契约 `outputs.<k>.share = SESSION`，节点完成时写入 |
| 读取表达式 | `shared.<k>` | `session.<k>` |
| Redis | `task:<tid>:shared` | `{c}:shared` |
| MySQL | `task_instance.shared` | `chat_conversation.session_shared` |
| 生命周期 | 随任务结束删除 | 跨任务保留 |

共享值只有通过契约 `share` 才会写入，没有其他写入途径。

**大对象**

| 对象 | 阈值 | 处理 |
|---|---|---|
| 工具报文 | `state.payload-inline-max-bytes` | Redis 保存完整报文；MySQL 另存 `task_node_payload`（`ARTIFACT`），证据头记录 `payloadId` |
| 节点空间单个字段 | `state.space-field-max-bytes` | MySQL 另存 `task_node_payload`（`OUTPUT`），Redis 中存放溢出占位 `{kind: "SPACE_SPILL", payloadId}` |
| 用户消息 | `state.message-max-bytes` | 截断 |

报文读取顺序：本轮内存 → Redis → `tool_call_record.response` → `task_node_payload`。

### 5.11 节点状态迁移

| 事件 | 迁移到 | 是否停图 |
|---|---|---|
| `SELECTED`（开始执行） | `RUNNING` | 否 |
| `LOOP_DONE` | `DONE` | 否 |
| `LOOP_SUSPENDED` | `SUSPENDED` | 是 |
| `LOOP_WAITING_DELEGATE` | `WAITING_DELEGATE` | 否 |
| `LOOP_FAILED` | `FAILED` | 是（委派前置节点除外） |
| `LOOP_CANCELLED` / `USER_CANCELLED` / `USER_REJECTED` | `CANCELLED` | 是（委派前置节点除外） |

状态机之外的迁移：

| 场景 | 迁移 |
|---|---|
| 上游满足依赖 | `PENDING` → `READY` |
| 用户回应断点 | `SUSPENDED` → `READY` |
| 前置节点到达终态 | `WAITING_DELEGATE` → `READY` |
| 中断规整 | `RUNNING` → `READY` |
| 所需共享值已存在 | → `SKIPPED` |
| 检测到不确定写操作且需用户核实 | → `SUSPENDED` |
| 本轮异常 | 当前节点和运行中的节点 → `FAILED` |

调度规则：每次只执行一个可调度节点。

---

## 6. 配置项

前缀 `g2rain.agent.runtime`。

### 6.1 state

| 配置 | 默认 | 作用 |
|---|---|---|
| `state.conversation-ttl` | 7d | 会话 `state`、会话共享的 TTL |
| `state.task-ttl-running` | 24h | 任务进行中的任务组 TTL |
| `state.task-ttl-suspended` | 2h | 任务挂起时的任务组 TTL |
| `state.sentinel-gap` | 10m | 续期时哨兵比同组 key 短的时长 |
| `state.payload-inline-max-bytes` | 524288 | 报文超过即另存 MySQL |
| `state.payload-done-ttl` | 1h | 节点完成后报文的 TTL；0 表示立即删除 |
| `state.evidence-entities-max-chars` | 2048 | 证据实体 JSON 的长度上限 |
| `state.state-size-log-enabled` | true | 是否记录状态大小日志 |
| `state.space-field-max-bytes` | 65536 | 节点空间字段溢出阈值 |
| `state.message-max-bytes` | 4096 | 用户消息截断长度 |
| `state.dialogue-max` | 60 | 对话窗口条数 |
| `state.records-max` | 20 | 任务记录条数 |
| `state.entities-max` | 200 | 实体索引上限 |
| `state.prior-tasks-max` | 5 | 前情任务条数 |
| `state.prior-entities-max` | 10 | 前情实体个数 |
| `state.flush-max-bytes` | 1048576 | 单次 flush 改动上限 |
| `state.reconfirm-after` | 24h | 审批断点过期时长 |
| `state.delegate-max-depth` | 2 | 委派最大深度 |
| `state.write-confirm-bypass-tools` | 空 | 免审批的写工具名单 |

### 6.2 react

| 配置 | 默认 | 作用 |
|---|---|---|
| `react.max-rounds` | 32 | 单节点最大轮数 |
| `react.max-no-progress-rounds` | 3 | 连续无进展上限 |
| `react.brief-max-chars` | 200 | 参数、结果摘要长度 |

---

## 7. 错误码

| 错误码 | 名称 | 含义 |
|---|---|---|
| `agent_runtime.40310` | `CONVERSATION_FORBIDDEN` | 无权访问该会话 |
| `agent_runtime.40911` | `STATE_FENCED` | 栅栏冲突，本轮写入被拒 |
| `agent_runtime.50010` | `STATE_WRITE_FAILED` | 状态写入失败 |
| `agent_runtime.50011` | `STATE_UNAVAILABLE` | 状态存储不可用 |
| `agent_runtime.40912` | `APPROVAL_EXPIRED` | 审批已过期 |
| `agent_runtime.40913` | `ARGS_HASH_MISMATCH` | 待批参数与确认时不一致 |
| `agent_runtime.40030` | `WRITE_PENDING_VERIFY` | 写操作结果待确认，禁止重复调用 |
| `agent_runtime.40031` | `WRITE_IN_FLIGHT` | 写操作进行中，禁止重复调用 |
| `agent_runtime.42237` | `DELEGATE_FORBIDDEN` | 禁止委派（不在目录内或超过深度） |
| `agent_runtime.42238` | `DELEGATE_BROKEN` | 委派关系损坏 |
| `agent_runtime.42239` | `DELEGATE_REPEATED` | 重复委派已失败的同一目标 |

以上错误码用于服务端日志、节点错误信息和给模型的观测，客户端只看到统一的失败文案（见 [SSE_EVENTS.md](SSE_EVENTS.md)）。

---

## 8. 实现约束与注意事项

以下是当前实现的行为边界，扩展或排查问题时需要注意：

| # | 约束 |
|---|---|
| 1 | Redis 中 `dialogue`、`records`、`entities` 三个 key 不设置 TTL；`entities` 没有 MySQL 回灌 |
| 2 | 任务组回灌按 key 逐个进行：Redis 中缺哪个 key 就从 MySQL 读哪个，不做整组替换 |
| 3 | 每次 flush 都整体重写本轮涉及的状态，而不是只写变化的字段 |
| 4 | MySQL 各表没有业务唯一索引，写入采用"先查后写" |
| 5 | 任务图每次只调度一个节点 |
| 6 | 节点空间的 `tokens` 当前不统计 |
| 7 | `TaskRecord.artifacts`、`Prior.artifacts` 当前为空 |
| 8 | persist 两次失败后不抛出异常，客户端仍会收到结果帧；状态在下一轮按中断规整 |
