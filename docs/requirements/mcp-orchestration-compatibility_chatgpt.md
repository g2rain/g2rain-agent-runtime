# MCP 编排、Skill 契约与内置交互工具的兼容性提升需求

状态：待实施  
涉及仓库：g2rain-agent-runtime、g2rain-agent-studio、g2rain-mcp-registry  
依据：上述仓库现有代码。本文区分“已实现”与“提升需求”，不把设计意图当作已交付能力。

## 结论

现有 Runtime 足以执行一类明确的 SaaS 业务任务：Agent 选择 Skill，规划器构建有依赖关系的节点，每个节点在 ReAct 循环中调用 MCP 工具；运行时按契约绑定参数、判定业务结果、记录证据，并允许追问、审批、委派、写正式输出和完成节点。对于“先查事实、补齐参数、经人确认写入、核实结果、交给下一 Skill”，架构链路已经存在。

现有设计还不能作为通用 MCP 工作流引擎来承诺。计划节点只表达技能、目标和前置依赖；普通边仅表示“上游完成后继续”。条件分支、结果汇聚、外部事件等待、失败去向和补偿没有一等图语义。模型可以在单个节点内选择下一次调用哪个工具，但该选择不是可检查、可恢复的图决策。

对旧 Skill 的兼容性不能直接保证。Skill 用工具注册名关联 Studio 绑定、工具契约、完成条件、缺参补全和待批动作；工具改名或改入参会连锁影响这些引用。任务续跑时会重新装载 Studio 的当前 Skill，旧图仍保留原有 skillKey，因而在途任务可能在新契约下恢复。升级应先解决这两处兼容边界。

~~~mermaid
flowchart LR
    A["Studio Agent / Skill / Tool Binding"] --> B["BundleLoader 装载契约"]
    B --> C["Planner 构建依赖图"]
    C --> D["NodeRunner 节点执行"]
    D --> E["ReAct 选择工具"]
    E --> F["ToolInvoker 参数、审批、证据、结果"]
    F --> G["MCP Tool"]
    G --> H["证据与 output"]
    H --> C
    E --> I["追问、审批、委派、完成、失败"]
    I --> C
~~~

## 已满足：原因和适用范围

| 能力 | 代码依据 | 判断 |
| --- | --- | --- |
| 静态节点依赖 | [PlanStep](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/domain/plan/PlanStep.java) 定义节点、Skill 和 dependsOn；[PlanGraphs](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/plan/PlanGraphs.java) 校验依赖和无环后落图。 | 能表达有向无环的先后步骤。 |
| 前置能力复用 | [ProviderNodes](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/plan/ProviderNodes.java) 按 inputs.provider 插入前置 Skill，已有共享输出可复用。 | “先查客户再处理订单”可在契约中声明。 |
| 节点内多次 MCP 调用 | [DefaultNodeRunner](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/graph/DefaultNodeRunner.java) 按 Skill 的 toolNames 装配 MCP 工具；[DefaultReActLoop](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/react/DefaultReActLoop.java) 逐次调用并观察结果。 | 一个 Skill 可完成多步查询和写入，无需每次工具调用都画一个节点。 |
| 业务动作契约 | [SkillContract](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/domain/hydrate/SkillContract.java) 定义输入、输出、目标工具、完成条件和工具用法；[ToolSpec](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/domain/hydrate/ToolSpec.java) 定义参数、成功判定、抽取、写入确认和核实；[DefaultFinishPolicy](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/tools/finish/DefaultFinishPolicy.java) 拦截未满足契约的完成请求。 | 适合有明确业务边界、需要可核查结果的任务。 |
| 人机交互与恢复 | [ControlBuiltinTools](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/tools/builtin/ControlBuiltinTools.java) 提供追问、审批、委派、完成和失败；[CheckpointType](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/domain/state/task/CheckpointType.java) 保存 FOLLOW_UP、APPROVAL、VERIFY 断点。 | 常见补参、确认写入、结果核实可以挂起并续跑。委派是在同一任务图中插入 Skill 前置节点，并非启动独立 Agent。 |
| 统一调用策略 | [DefaultToolInvoker](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/tools/DefaultToolInvoker.java) 串联调用前后拦截器；[WriteGateInterceptor](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/tools/guard/WriteGateInterceptor.java) 处理写入门禁；[EvidenceInterceptor](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/tools/guard/EvidenceInterceptor.java) 落证据。 | 新的工具规则有统一扩展位置。 |

## 不满足：具体边界和影响

### 1. 节点粒度能表达依赖，不能稳定表达业务分支

[TaskGraph.nextWave](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/domain/graph/TaskGraph.java) 当前每次只取第一个可运行节点；[EdgeType](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/domain/graph/EdgeType.java) 只有 NORMAL 和 DELEGATE。普通依赖要求上游 DONE 或 SKIPPED；失败不会走另一条显式业务分支。SKIPPED 已存在，但目前没有通用的“按上游结果选择哪个计划节点执行”语义。

例如“查库存；有货创建订单，无货通知补货；最后统一回执”，可以放在一个 Skill 的 ReAct 手册中让模型控制，却无法由图记录分支选择与汇合。流程越长，越难检查其路径和恢复行为。提升应在图层增加可选条件、跳过原因、失败去向和汇聚规则，条件只引用已核实的 output 或证据。旧 NORMAL 边没有条件时必须照旧执行。

### 2. 契约可约束单次动作，尚未校验整条能力引用链

[DefaultBundleLoader](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/hydrate/DefaultBundleLoader.java) 解析 Studio 的 Skill 契约并经 [ContractValidator](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/hydrate/ContractValidator.java) 校验，但校验入口不接收 Studio 的 toolNames 或 MCP 实际清单。[NodeToolset.of](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/graph/NodeToolset.java) 以 toolNames 决定可调用工具，contract.tools 仅作可选用法。[ReactProjectionFactory.toolItem](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/react/ReactProjectionFactory.java) 会从提示投影中省略未声明 ToolSpec 的业务工具，模型侧工具定义却仍由回调列表提供。

因此存在“能调用却缺少预期业务契约和提示”以及“契约引用了一个当前不可用工具”的空间。target、doneWhen、缺参 CALL_TOOL、证据和待批动作又依赖工具名。一旦 MCP 工具改名或 schema 变化，Skill 可能装载成功而执行失败。[McpToolCallbackProvider](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/support/mcp/McpToolCallbackProvider.java) 将允许名单放进 tools/list 元数据，当前 [McpToolListHandler](../../../g2rain-mcp-registry/g2rain-mcp-registry-biz/src/main/java/com/g2rain/mcp/registry/tool/McpToolListHandler.java) 会交给 Registry 过滤，但 Runtime 对响应只检查工具名非空，未再次与本 Skill 允许名单求交集。这使工具范围依赖 Registry 的正确响应，未来接入其他 MCP 服务时边界尤其需要明确定义。

提升应在 Studio 发布和 Runtime 装载时对绑定工具、契约工具、目标、完成条件、缺参工具和 MCP 清单做整体预检；运行时也应只暴露允许名单与实际清单的交集，并报告缺失的必需工具。允许明确声明“仅调用、无额外业务契约”的读工具，写工具继续要求完整成功条件。预检需要精确指出 Skill、工具和参数路径。

### 3. 内置交互工具覆盖常见动作，但缺少结构化选择和外部等待

现有 builtin_node_follow_up 接收问题、待补字段和建议；builtin_node_approval 只承接写闸拦下的单次写调用；builtin_node_delegate 只可插入允许委派的前置 Skill；builtin_node_finish/fail 结束节点。其粒度适合“问一个值、确认一次写入、先做一个只读前置任务”。

代码中的断点仅有 FOLLOW_UP、APPROVAL、VERIFY；[ResumeAction](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/domain/state/task/ResumeAction.java) 仅有 REPLY、CONFIRM、REJECT、CANCEL。对于“从多个候选对象中选一个”“外部系统回调后继续”“人工修改中间产物”“一批写动作统一审批”，现在只能部分借助文本追问或手册约定，运行时无法把选择结果和恢复条件作为独立事实校验。应在现有追问和审批上增量增加结构化结果，并为外部事件设计独立断点。旧工具名、参数和恢复动作保持有效。

### 4. MCP 返回处理以文本为中心

[McpToolCallback](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/support/mcp/McpToolCallback.java) 只拼接 TextContent，跳过其他内容块；[DefaultToolInvoker](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/tools/DefaultToolInvoker.java) 尝试把该文本解析为 JSON。当前 OpenAPI 风格工具返回 JSON 文本时，success、error、extracts 可以工作；结构化结果和多内容块则没有完整进入证据与 output 链路。Runtime 当前对接的是 MCP tools，不能据此宣称覆盖 MCP 的全部资源或提示能力。

提升应在适配层保留模型摘要、结构化数据、内容引用和协议错误。旧 Skill 的 success.path 与 extracts.path 仍指向原先文本解析出的 JSON；新 Skill 才显式选择结构化结果路径。不能静默改变旧路径的根对象。

### 5. 在途任务未固定其 Skill 和工具解释

[DefaultTurnPipeline](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/DefaultTurnPipeline.java) 每次进入执行阶段都调用 Hydrator；[DefaultHydrator](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/hydrate/DefaultHydrator.java) 从 Studio 重新加载 Agent Bundle；[DefaultPlanner](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/engine/flow/plan/DefaultPlanner.java) 对续跑任务沿用旧图；[TaskMetadata](../../g2rain-agent-runtime-biz/src/main/java/com/g2rain/agent/runtime/domain/state/task/TaskMetadata.java) 只有 Agent ID，图节点只有 skillKey，未见持久化的 Skill 执行绑定。

如果任务审批挂起期间有人修改 Skill Contract、Tool Binding 或 MCP 名称，恢复时可能用旧图加新契约；原待批动作甚至可能找不到工具。应在建任务时固定手册、契约、工具逻辑名和可执行工具描述；续跑沿用该任务绑定。新配置只影响新任务。对历史任务无法匹配的情况要明确报告和迁移，不能静默套用新语义。

## 待实施改造与验收

### P0：先保证旧 Skill 和在途任务可继续执行

责任：Studio、Runtime、MCP Registry。

1. 抽取仓库现有已发布 Skill 作为回归样本，包括单 Skill 多工具、PLAN、REACT、查询空结果、写入审批、委派、跨 Skill output 和缺参 CALL_TOOL。
2. 建立发布预检：整体核对 Studio Tool Binding、Skill Contract 的工具引用与 MCP 名称和输入 schema。Runtime 对 tools/list 返回再次按允许名单求交集，并报告必要工具缺失。旧 Skill 先报告差异；新增或修改的 Skill 使用更严格的发布门槛，避免一次升级导致存量 Skill 全部不可用。
3. 在任务创建时固定执行绑定；续跑使用固定绑定。MCP 改名通过旧逻辑名别名适配，至少覆盖所有仍在执行的任务。
4. 固定已有缺省语义：mode 缺省 REACT，输入和输出 required 缺省 true，sideEffect 缺省 NONE，WRITE 缺省需要确认。任何默认解释变更都须显式迁移 Skill。

验收：同一组旧 Skill 在改造前后，节点顺序、实际工具、绑定参数来源、审批断点、成功判断和正式 output 一致。任务挂起期间修改 Studio 配置后，确认续跑仍按创建任务时的能力执行；若外部工具已不可恢复，返回明确可处理的原因。

### P1：补充 MCP 结果与交互粒度

责任：Runtime、MCP Registry；Studio 更新编辑和预检展示。

1. 工具结果同时保存文本摘要和结构化数据，保留旧 success/extracts 路径语义；新路径须显式声明。
2. 追问支持结构化候选和选择值，外部事件支持独立等待断点；用户选择与外部回调应成为带来源的节点事实，并能传给下游 Skill。
3. 旧 follow-up、approval 调用和恢复动作继续被接受。

验收：旧 JSON 文本型 Skill 结果不变；新结构化 MCP 工具可直接用于业务成功判断和抽取；新选择与外部事件在跨轮恢复后仍取得同一个业务值。

### P2：按业务场景扩展图决策

责任：Runtime；Studio 提供流程配置与预检。

1. 增加基于正式 output 或证据的条件分支、显式跳过和失败去向，定义汇聚节点对失败、跳过和空结果的处理。
2. 旧 PlanStep 不增加必填字段，旧 NORMAL 边继续按原依赖语义执行。
3. 已做出的分支选择要写入任务事实，恢复时不得重新让模型决定既有路径。

验收：一个流程能稳定执行“有结果走 A、无结果走 B、任一路径结束后汇合”；旧计划和旧 Skill 的路径保持一致。并行、循环、补偿分别需要独立的状态与恢复设计，不能仅通过放开当前单节点调度来宣称完成。

## 兼容规则

| 后续变更 | 对旧 Skill 的约束 |
| --- | --- |
| 新增内置工具或可选契约字段 | 原字段的缺省解释不变；原工具名和参数继续注册。 |
| MCP 工具改名或改输入 schema | 旧逻辑名保留适配；发布预检指出差异；target、doneWhen、CALL_TOOL 和待批动作仍能定位同一能力。 |
| MCP 返回形状变化 | 旧 success/extracts 路径继续读取旧数据视图；新路径由 Skill 显式选择。 |
| 修改已发布 Skill | 新任务使用新配置；在途任务继续使用创建时的执行绑定。 |
| 新增分支或交互断点 | 采用可选字段或新工具；旧 NORMAL 边与旧恢复动作保持原义。 |

验证应使用 Studio 仓库现有的多工具业务 Skill 和跨 Skill 传值案例，包括契约装载、图状态、MCP 模拟调用、审批挂起后的配置变化、旧新结果路径对照。本文是基于代码的静态评估和实施需求；上述改造与验收尚未执行。
