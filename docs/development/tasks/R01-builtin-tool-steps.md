# R01 · 内置工具调用全部推送步骤事件

## 目标

任何工具调用（含内置工具）都推送 `PROGRESS` 的 `TOOL` 步骤，让用户看到智能体在处理；步骤标题按页面语言显示。

## 现状

- `engine.tools.ToolEvents.started` 对内置工具按 `BuiltinToolAction.step()` 取标题；只有 `ASK`、`FAIL`、`FINISH`、`SKIP`、`APPROVAL`、`DELEGATE` 配了文案，`READ`、`WRITE`、`OUTPUT`、`QUERY` 为空，返回 `null` 后不推送步骤。读写任务状态、查阅证据、读取会话信息等调用在客户端不可见，界面上只看到推理轮次。
- 同一动作下有多个工具（如 `QUERY`），按动作无法给出各自的标题。

## 依据

- `support.tool.BuiltinToolKind`、`BuiltinToolAction`；`engine.tools.ToolEvents`；`engine.tools.builtin.*BuiltinTools`
- `i18n.RuntimeMessage`；`g2rain-infra/scripts/g2rain-infra.sql` 中 `UI_MESSAGE` / `AGENT_RUNTIME` 的 `step.*` 行
- `docs/SSE_EVENTS.md`

## 范围

1. `@BuiltinToolKind` 新增属性 `step`（类型 `RuntimeMessage`），缺省值 `RuntimeMessage.STEP_TOOL_DEFAULT`（「调用工具」）。标题文案只来自 `RuntimeMessage`，不在注解里写字符串。
2. `BuiltinToolAction` 删除 `step` 字段及其访问方法，不做兼容；动作只表达语义。
3. 各内置工具在注解上配置 `step`：

   | 工具 | `step` |
   | --- | --- |
   | `builtin_node_follow_up` | `STEP_ASK` |
   | `builtin_node_approval` | `STEP_APPROVAL` |
   | `builtin_node_delegate` | `STEP_DELEGATE`（沿用 `{skill}` 占位） |
   | `builtin_node_finish` | `STEP_FINISH` |
   | `builtin_node_skip` | `STEP_SKIP` |
   | `builtin_node_fail` | `STEP_FAIL` |
   | `builtin_output_set` | 新增 `STEP_OUTPUT`（「记录节点产出」） |
   | `builtin_state_set` | 新增 `STEP_STATE`（「记录任务状态」） |
   | `builtin_evidence_get` | 新增 `STEP_EVIDENCE`（「查阅已取得的结果」） |
   | `builtin_session_info` | 新增 `STEP_SESSION`（「读取会话信息」） |

   `EnvBuiltinTools` 的工具暂不配置，使用缺省值（该类目前未使用，保留待定）。
4. `BuiltinToolCallback`（或其构造处）从注解读取 `step`，提供给 `ToolEvents`；`ToolEvents.started` 对内置工具始终推送步骤，不再返回 `null`；委派仍按技能标题填充 `{skill}`。`finished` 的现有行为不变。
5. 新增文案的 zh-CN、en-US、ja-JP 三种语言写入 `g2rain-infra.sql` 末尾的独立 INSERT（`UI_MESSAGE`、`AGENT_RUNTIME`），主键接在当前最大值之后，同步 `g2rain-deploy/config/mysql`。
6. `docs/SSE_EVENTS.md`：步骤说明改为「每次工具调用（含内置工具）都推送 `TOOL` 步骤」，列出内置工具的步骤标题。

## 不做

- 不改变工具执行、拦截、记录（`tool_call_record`）的任何行为。
- 不删除 `EnvBuiltinTools`。

## 验收

- `ToolEventsTest`：每个内置工具调用都推送步骤，标题为注解配置的文案；未配置的工具为「调用工具」；委派带技能标题。
- 每个内置工具方法都带 `@BuiltinToolKind`（可用一个反射测试扫描 `*BuiltinTools` 类断言），`step` 取值存在于 `RuntimeMessage`。
- `mvn clean verify` 通过。

## 手工验证

- 前端发起一次含读写任务状态的对话，步骤列表中出现对应的内置工具步骤，切换页面语言后标题随之变化。
