# D-4：跨轮重建历史时使用完整参数（方案 A）

| 项 | 内容 |
|---|---|
| 状态 | 已实现（2026-11-22，方案 A） |
| 修复条目 | 扶正清单 D-4 |
| 涉及类 | `NodeHistory`、`RuntimeStateFacade` / `DefaultRuntimeStateFacade`、`DefaultNodeRunner`、`DefaultReActLoop` |
| 不涉及 | `EvidenceHead`（`argsBrief` 仍用于观测投影、客户端展示和日志）、`tool_block_record`、表结构 |
| 关联文档 | [状态模型](../STATE_SPEC.md) 3.3（节点空间 `actions`）、[第二批 B1](evidence-scope.md)（`argsOf` 批量查询） |

---

## 1. 问题

节点跨轮续跑时，`NodeHistory.build` 重建 ReAct 历史。历史中工具调用的参数有两种来源，都是**截短后的摘要**：

| 调用类型 | 参数来源 | 现状 |
|---|---|---|
| 产生了证据的业务调用 | `EvidenceHead.argsBrief` | 敏感字段打码，长字符串截短，过大的容器折叠（上限 `react.brief-max-chars`，默认 200） |
| 没有证据的调用（内置工具、执行前被拒、**被写闸门推迟待确认的写调用**） | `NodeAction.arguments`，由 `DefaultReActLoop.argumentsOf` 用 `ArgsBrief.of` 生成 | 同上（上限 `state.node-action-max-chars`，默认 2048） |

**同一轮内**的历史用的是模型自己发出的完整调用，不受影响；只有**跨轮重建**时才会用到这些摘要。

**风险场景**：写调用被闸门推迟（记为动作，参数是摘要）→ 节点追问用户 → 用户回答，节点续跑 → 模型重新发起写调用时照抄历史里的参数 → 文章正文这类长字段是截短后的 → 审批卡显示的就是残缺内容，用户没留意就确认了。

## 2. 设计

**跨轮历史中的工具调用参数：只脱敏、不截短。**

| 调用类型 | 新的参数来源 |
|---|---|
| 有证据的业务调用 | `tool_call_record.request`（完整入参），用 B1 的 `argsOf` **一次批量**取回；再用 `ArgsBrief.masked(args, toolSpec)` 打码敏感字段。取不到时退回 `argsBrief` |
| 没有证据的调用 | 记录动作时就保存**脱敏后的完整参数**：`DefaultReActLoop.argumentsOf` 改用 `ArgsBrief.masked`，不再按 `node-action-max-chars` 截短 |

- 敏感字段仍按契约打码（`***`），与现在一致。
- 历史体积会变大，但这些参数本来就是模型发出过的，与原来那一轮的体积相当。
- 动作的参数存放在节点空间的 `actions` 字段中。字段过大时会走现有的溢出机制（`space-field-max-bytes` → `task_node_payload`），不会撑大 Redis。
- `state.node-action-max-chars` 以后只用于截短动作的**说明**（`message`），不再用于参数，配置说明随之更新。

## 3. 代码改动

### 3.1 `RuntimeStateFacade.history`

签名增加技能契约，用于打码：

```java
List<ChatTurn> history(NodeScope node, List<ToolCallback> tools, @Nullable SkillContract contract);
```

`DefaultNodeRunner.historyFor` 传入 `skill.contract()`（技能为空时传 null）。

### 3.2 `DefaultRuntimeStateFacade.history`

1. 收集本节点全部证据的 evidenceId，调用一次 `argsOf(turn, ids)`。
2. 对每条取到的入参，用 `ArgsBrief.masked(args, contract.tool(toolName))` 打码，序列化为 JSON 文本，组成 `Map<String, String>`（evidenceId → 参数文本）。
3. 把这个 Map 传给 `NodeHistory.build`。

### 3.3 `NodeHistory.build`

新增参数 `Map<String, String> callArgs`。组装证据对应的 `ToolCallPart` 时，优先取 `callArgs.get(evidenceId)`，取不到再用 `argsBrief`，再取不到用 `"{}"`。

### 3.4 `DefaultReActLoop.argumentsOf`

改为 `JSON.obj2str(ArgsBrief.masked(JSON.str2node(raw), spec))`。解析失败时仍返回原文（与现状一致）。方法 Javadoc 同步改为“按契约脱敏，不截短”。

## 4. 文档同步

| 位置 | 修改 |
|---|---|
| STATE_SPEC 3.3 `NodeAction.arguments` | 改为“按契约脱敏的完整入参（不截短）” |
| STATE_SPEC 3.3（节点历史重建说明，若有） | 补充“跨轮历史中的调用参数为脱敏后的完整入参：有证据的取 `tool_call_record.request`，没有证据的取动作上保存的参数” |
| STATE_SPEC 第 7 节 `state.node-action-max-chars` | 改为“无证据动作说明的字符上限” |
| `RuntimeStateFacade.act` 的 Javadoc | 同上 |

---

## 5. 任务卡（交给 Cursor）

**卡片 D4：跨轮历史使用完整参数**

**前提**：先读本文全文，以及 `docs/development/code-conventions.md` 的“编码细则”。只改本文开头表格中列出的类和文档。

**用例**：

| # | 场景 | 期望 |
|---|---|---|
| A1 | `history`：节点有一条证据，`request` 中有一个长度 5000 的字符串字段和一个敏感字段 | 历史中该调用的参数：长字段完整，敏感字段为 `***` |
| A2 | `history`：某条证据查不到 `request` | 该调用的参数为 `argsBrief` |
| A3 | `history`：节点有 3 条证据 | `argsOf` 只被调用一次，参数含全部 3 个 id |
| A4 | `DefaultReActLoop`：写调用被闸门推迟，参数含长度 5000 的字段和敏感字段 | 记录的动作 `arguments`：长字段完整，敏感字段为 `***` |
| A5 | `history`：契约为 null | 不打码，参数为完整入参 |

**验收**：A1～A5 全部通过；原有测试仍然通过；IDEA 无新增告警；文档已同步。
