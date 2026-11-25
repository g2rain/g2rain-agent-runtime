# R03 · 对话请求可指定技能修订

## 目标

对话请求可以指定某个技能按某条修订运行（例如在不影响线上的情况下验证一条草稿）；任务续跑时沿用同一修订。

## 依据

- Studio S02：技能包接口的可选参数 `skillRevisionId`
- Runtime：`engine.TurnRequest`（`agentId`、`skillKey` 的写法与校验）、`client.AgentProfileClient` / `AgentProfiles#loadCapabilityBundle`、`engine.flow.hydrate.DefaultBundleLoader`、`domain.state.task.TaskMetadata`、`engine.state.repo.TaskRepository`、`docs/SSE_EVENTS.md`、`docs/STATE_SPEC.md`

## 范围

只改 `g2rain-agent-runtime`。

1. `TurnRequest` 新增 `skillRevisionId`（Long，可选）：须同时给 `agentId`（否则沿用 `skillKey` 的参数错误处理方式），只在开新任务时生效；继续挂起任务或回应按钮时忽略。
2. 任务锁定：`task_instance` 新增 `skill_revision_id BIGINT DEFAULT NULL COMMENT '开任务时指定的技能修订'`（放在 `spec_pins` 之后），手工补 PO、DTO、SelectDto、VO、Mapper XML（不重跑生成器）；`TaskMetadata` 新增 `skillRevisionId`；同步 `g2rain-deploy`。
3. 装载技能包时，把任务上的 `skillRevisionId` 原样传给 Studio（`AgentProfileClient` 增加参数）；Runtime 不解释其含义。
4. 文档：`SSE_EVENTS.md` 的请求字段表、`STATE_SPEC.md` 的任务元数据。

## 不做

- 数字员工的修订。

## 验收

- 新任务带 `skillRevisionId`：落到任务上，装载时传给 Studio；续跑时沿用；不带时行为不变。
- 只给 `skillRevisionId` 不给 `agentId`：参数错误。
- `mvn clean verify` 通过；交付说明给出测试环境的 `ALTER TABLE`。
