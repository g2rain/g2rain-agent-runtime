# 意图路由与选人设计

本文档描述 Runtime 每一轮如何决定"任务怎么推进"和"交给哪个数字员工"，覆盖入口分流、意图分类、结果校正与选人。内容是 `engine.flow.entry`、`engine.flow.decision` 与 `domain.decision` 的设计依据；本轮的回复文案也在选人环节确定，后续环节只负责输出，实现以本文为准。

---

## 目录

1. [目标与非目标](#1-目标与非目标)
2. [整体流程](#2-整体流程)
3. [关键设计决策](#3-关键设计决策)
4. [提示词](#4-提示词)
5. [代码改动](#5-代码改动)
6. [验收用例](#6-验收用例)
7. [单元测试](#7-单元测试)
8. [关联文档更新](#8-关联文档更新)

---

## 1. 目标与非目标

### 1.1 目标

| # | 目标 |
|---|---|
| G1 | 新对话、寒暄、普通办事请求不出现澄清追问 |
| G2 | 用户回答补参问题时直接续跑，不调用意图模型 |
| G3 | 指定 agentId 时，本轮只在该员工上判断，不换人；明显超出其职责时直接说明 |
| G4 | 待确认的写操作只能由显式确认动作推进，文字输入不能触发写操作 |
| G5 | 意图模型返回空、输出无法解析或模式非法时，有确定的兜底且有日志 |
| G6 | 提示词是一份完整可读的文本；"提示词允许的模式"与"代码接受的模式"来自同一份清单 |

### 1.2 非目标

- 不为"指定员工"或"单员工租户"维护独立的提示词模板。
- 不在意图阶段合并技能预选。
- 不引入解码层的 JSON 约束（guided decoding / response_format）。

---

## 2. 整体流程

```
DefaultTurnEntryGuard（入口，确定性规则，不调用模型）
 ├─ 显式动作：确认 / 拒绝 / 取消 / 回复        → 续跑挂起任务
 ├─ 请求 Agent 与挂起任务绑定 Agent 不一致      → 换人
 ├─ FOLLOW_UP 断点 + 无显式动作                 → 按 REPLY 续跑（用户输入即补参内容）
 ├─ APPROVAL / VERIFY 断点 + 无显式动作 + 有文字 → 交给调度器
 └─ 无挂起任务                                   → 交给调度器

DefaultTurnDispatcher（调度器）
 ├─ 场景  scene = TurnScene.of(有活动任务, 有任务前情)
 ├─ 候选  指定 agentId 时只保留该员工，技能目录不截断
 ├─ 分类  DefaultIntentClassifier：SYSTEM + USER（完整模板，填入场景、上下文、用户原话）
 └─ 校正  settle()
          ① 结论缺失或不在 scene.modes() 中 → scene.fallback()
          ② 有待确认写操作且结论为 CONTINUE_TASK → UNCERTAIN
          ③ 需要选人但 agentId 为空、且给出了 userReply → REPLY_ONLY
          结果为 UNCERTAIN 时写入确认文案：VERIFY 断点用 VERIFY_ASK，其余用 ROUTE_UNCERTAIN

DefaultAgentResolver（选人）
 显式 agentId → 续跑锁任务 Agent → 意图 agentId（仅选人模式）→ 当前任务 Agent → 前情 Agent → 租户唯一 Agent → 无人可选

DefaultFinalizer（收口，不改动）
 UNCERTAIN / REPLY_ONLY 输出 IntentResult.userReply
```

### 2.1 场景与可选模式

| 场景 | 判定 | 可选模式（按规则匹配顺序） | 兜底 |
|---|---|---|---|
| `FRESH` | 无活动任务，无前情 | START_FRESH、REPLY_ONLY | START_FRESH |
| `CONTINUITY` | 无活动任务，有前情 | START_WITH_CONTEXT、START_FRESH、REPLY_ONLY | START_FRESH |
| `ACTIVE_TASK` | 有 RUNNING / SUSPENDED 任务 | CANCEL_TASK、SWITCH_AGENT¹、REPLACE_TASK、CONTINUE_TASK、REPLY_ONLY | CONTINUE_TASK² |

¹ 指定 agentId 时不可选。
² 有待确认写操作时，经校正第 ② 步变为 UNCERTAIN。

`UNCERTAIN` 不在任何场景的可选模式中，意图模型不输出它，只由校正第 ② 步产生。

---

## 3. 关键设计决策

每项列出采用方案、备选方案与取舍理由。

### D1 补参回复在入口直接续跑

- **采用**：FOLLOW_UP 断点下，无显式动作即按断点默认动作 REPLY 续跑，无论用户是否输入文字。
- **备选**：交给意图模型判断是否在补参。
- **理由**：补参回答通常很短（"上海""是的"），让模型判断既慢又容易误判。用户在补参时改口（"算了，换个事"）由执行环节识别处理，这比把每次补参都交给模型的风险小得多。

### D2 意图模型不输出 UNCERTAIN

- **采用**：可选模式中没有 UNCERTAIN，它只由代码在"有待确认写操作 + 判为续跑"时产生。
- **备选**：允许模型在拿不准时输出 UNCERTAIN。
- **理由**：澄清追问唯一必要的场景是写操作安全，这是确定性规则，不应依赖模型是否听话。其它拿不准的情况，按最可能的意图推进，体验优于追问。

### D3 场景由代码确定

- **采用**：`TurnScene` 由运行态算出，写入提示词【当前场景】，模型只在该场景的规则内判断。
- **备选**：给模型若干布尔字段，由模型自行推断场景。
- **理由**：场景是确定事实，不应交给模型推断；同一份 `TurnScene.modes()` 同时用于提示词说明与结果校验，两者不会不一致。

### D4 提示词为完整静态文本

- **采用**：`IntentPrompt.USER` 是一份完整模板，三个场景的规则都写在其中，代码只填入场景名、上下文 JSON 和用户原话三个值。
- **备选**：按场景动态拼接，只渲染当前场景的规则。
- **理由**：完整文本能直接审阅、调优、比对版本；动态拼接让最终提示词只能在运行时看到。代价是模型多读两组不适用的规则（约十余行），由【当前场景】明确指向，影响很小。

### D5 指令用自然语言，上下文用 JSON

- **采用**：任务说明、规则、用户原话、输出指令用自然语言；会话上下文用 JSON（`NON_NULL`）。
- **备选**：全部渲染为自然语言分段。
- **理由**：规则通过字段名（`continuation`、`activeTask`、`agentLocked` 等）精确引用数据；JSON 由序列化直接得到，加字段无需改渲染代码。user 消息以明确的输出指令结尾，模型的生成起点紧跟指令。如果后续观察到模型对对话记录理解偏差，再单独把 `dialogue` 改为文本分段。

### D6 分类失败按场景兜底

- **采用**：返回空、无法解析、模式非法或调用异常时，取 `scene.fallback()`，并记录原因日志。
- **备选**：统一返回澄清追问。
- **理由**：分类失败是系统问题，不应转嫁给用户回答。兜底模式都不会触发写操作：写操作由校正第 ② 步拦截。

### D7 指定数字员工用同一模板

- **采用**：`agentLocked=true` 写入上下文，候选只保留该员工、技能目录不截断，规则中说明"只判断能否处理"，可选模式去掉 SWITCH_AGENT。
- **备选**：为指定员工维护独立模板。
- **理由**：差异只有三点，用数据和一条规则即可表达，不必维护第二份模板。

### D8 无法匹配员工时仅回复说明

- **采用**：需要选人但 agentId 为空、且 userReply 有说明时，校正为 REPLY_ONLY，把说明返回给用户。
- **备选**：本轮标记为 FAILED。
- **理由**：这是正常的业务结论（不在服务范围），不是系统失败，任务生命周期也不应受影响。

### D9 寒暄由意图模型直接回复

- **采用**：REPLY_ONLY 时，模型在 userReply 中给出简短回复。
- **备选**：统一返回固定文案"好的。"。
- **理由**：同一次模型调用顺带产出回复，没有额外成本；固定文案对"你好"之类的输入不自然。

---

## 4. 提示词

`support/prompt/IntentPrompt.java`：

```java
/**
 * 本轮意图分类提示词。
 *
 * <p>{@link #SYSTEM} 说明身份与职责；{@link #USER} 是完整的本轮指令模板，结构为
 * 任务 → 场景 → 规则 → 上下文 → 用户输入 → 输出指令。</p>
 */
public final class IntentPrompt {

    /**
     * 身份与职责。占位符为回复语言 BCP-47 标签（如 ja、en-US）。
     */
    public static final String SYSTEM = """
        你是企业智能助手的意图路由器，负责判断用户本轮输入如何推进任务，并选择执行的数字员工。
        只依据给定信息判断，不得臆造。
        给用户看的文字（userReply）一律使用语言标签 %s（BCP-47），优先于用户输入语种与历史对话语种。
        """;

    /**
     * 本轮指令模板。占位符依次为：当前场景、会话上下文 JSON、本轮用户输入。
     * <pre>{@code
     * String user = IntentPrompt.USER.formatted(scene.name(), contextJson, userInput);
     * }</pre>
     */
    public static final String USER = """
        请根据以下规则和会话上下文，判断本轮用户输入如何推进任务，并在需要时选择执行的数字员工。

        【当前场景】
        %s

        【推进规则】
        按当前场景对应的一组规则判断，从上到下取第一条符合的。

        FRESH（新对话）：
        - 需要办理事项 → START_FRESH
        - 寒暄、致谢、闲聊等无需办理的内容 → REPLY_ONLY

        CONTINUITY（无进行中的任务，上一个任务的摘要见 continuation）：
        - 新的事项明确依赖上一个任务的结果 → START_WITH_CONTEXT
        - 需要办理的其它事项 → START_FRESH
        - 寒暄、致谢、闲聊等无需办理的内容 → REPLY_ONLY

        ACTIVE_TASK（有进行中的任务，见 activeTask）：
        - 只取消当前任务、不再办理 → CANCEL_TASK
        - 明确要求换一个数字员工 → SWITCH_AGENT（agentLocked=true 时不可用）
        - 放弃当前任务、改办别的事 → REPLACE_TASK
        - 仍在推进当前任务，包括回答它的提问 → CONTINUE_TASK
        - 与任务无关的寒暄、致谢、闲聊 → REPLY_ONLY

        【选人规则】
        - 只有 START_FRESH、START_WITH_CONTEXT、SWITCH_AGENT 需要选人，从 agentCandidates 中选一个 agentId；其它模式 agentId 为 null。
        - 按候选职责和 skillCatalog 做语义匹配；表达口语化或模糊时选最贴近者，难以区分时选排在前面的；只有一个候选时直接选它。
        - START_WITH_CONTEXT 优先选择 continuityAgentId。
        - agentLocked=true 表示已指定数字员工（agentCandidates 中唯一的一项）；寒暄或说法模糊但可能相关时，视为它能处理。
        - 只有诉求与候选职责明显无关时，agentId 为 null，并在 userReply 中说明可以办理的事项，举 1～2 个例子。

        【userReply】
        - REPLY_ONLY：给用户的简短回复。
        - 无法匹配数字员工：说明可以办理的事项。
        - 其它情况为 null。

        【会话上下文】
        %s

        【本轮用户输入】
        %s

        【输出】
        请按当前场景的推进规则判断本轮用户输入，并按选人规则选择数字员工，只输出如下格式的一个 JSON 对象，不要输出其它任何文字：
        {"mode":"<当前场景可选的模式>","agentId":<agentId 或 null>,"goalSummary":"<一句话概括用户目标>","userReply":<文本或 null>}
        """;

    private IntentPrompt() {
    }
}
```

自审要点：

- 结尾指令同时包含任务和格式；输出格式只给字段说明，不给可能被照抄的具体值。
- 每个场景都有 REPLY_ONLY 出口；"必须选人"与"无法匹配时为 null"在同一条规则链中先后说明，不矛盾。
- 规则引用的字段名与上下文 JSON 的键一致：`continuation`、`activeTask`、`agentCandidates`、`skillCatalog`、`continuityAgentId`、`agentLocked`。

---

## 5. 代码改动

| 类 | 改动 |
|---|---|
| `domain.decision.TurnScene` | 新增：场景、可选模式、兜底模式 |
| `domain.decision.TurnMode` | 新增 `selectsAgent()`、`@JsonCreator of(String)`；更新 UNCERTAIN 注释 |
| `domain.decision.IntentResult` | `complete()` 只为选人模式保留候选内的 agentId；删除 UNCERTAIN 补文案分支 |
| `support.prompt.IntentPrompt` | 拆为 `SYSTEM` + `USER` |
| `engine.flow.decision.IntentRequest` | 用 `scene`、`agentLocked` 取代两个布尔字段；`scene`、`userInput` 不进 JSON；`NON_NULL` |
| `engine.flow.decision.DefaultIntentClassifier` | 用 `USER` 模板组装；失败原因日志 |
| `engine.flow.decision.DefaultTurnDispatcher` | 场景、候选收窄、`settle()`、`decisionOf()`、确认文案写入；删除 `remember()`、`postValidate()` |
| `engine.flow.decision.DefaultAgentResolver` | 按最终模式判断是否选人；删除"无法匹配即失败"分支 |
| `engine.flow.entry.DefaultTurnEntryGuard` | FOLLOW_UP 断点默认 REPLY 续跑 |
| `i18n.RuntimeMessage` | `ROUTE_UNCERTAIN` 只改文案，不改名；用户可见文案按请求语言从 Infra 的 `AGENT_RUNTIME` 消息集中读取，缺失时回退中文 |

`IntentResult.useContinuity` 保留，由 mode 推导；现有读取方不受影响。`DefaultFinalizer` 不改动：它对 UNCERTAIN / REPLY_ONLY 本就优先输出 `IntentResult.userReply`。

### 5.1 TurnScene

```java
package com.g2rain.agent.runtime.domain.decision;

import java.util.List;

/**
 * 本轮意图判定所处的会话场景。
 *
 * <p>场景由运行态确定性得出，给出意图模型可选的推进模式（按规则匹配顺序排列）与模型无有效结论时的兜底模式。
 * 提示词中的场景规则与 {@link #modes(boolean)} 保持一致，调度器据此校正模型结论。</p>
 *
 * <pre>{@code
 * TurnScene scene = TurnScene.of(false, true);   // CONTINUITY
 * scene.modes(false);                             // [START_WITH_CONTEXT, START_FRESH, REPLY_ONLY]
 * scene.fallback();                               // START_FRESH
 * }</pre>
 *
 * @author alpha
 * @since 2026/10/10
 */
public enum TurnScene {

    /**
     * 新对话：没有活动任务，也没有可接续的任务前情。
     */
    FRESH(TurnMode.START_FRESH, List.of(TurnMode.START_FRESH, TurnMode.REPLY_ONLY)),

    /**
     * 可接续：没有活动任务，上一任务留有存活前情。
     */
    CONTINUITY(TurnMode.START_FRESH,
        List.of(TurnMode.START_WITH_CONTEXT, TurnMode.START_FRESH, TurnMode.REPLY_ONLY)),

    /**
     * 任务进行中：存在运行或挂起中的任务。
     */
    ACTIVE_TASK(TurnMode.CONTINUE_TASK, List.of(TurnMode.CANCEL_TASK, TurnMode.SWITCH_AGENT,
        TurnMode.REPLACE_TASK, TurnMode.CONTINUE_TASK, TurnMode.REPLY_ONLY));

    /**
     * 模型无有效结论时的兜底模式。
     */
    private final TurnMode fallback;

    /**
     * 本场景可选模式，顺序即规则匹配顺序。
     */
    private final List<TurnMode> modes;

    TurnScene(TurnMode fallback, List<TurnMode> modes) {
        this.fallback = fallback;
        this.modes = modes;
    }

    /**
     * 由运行态得出场景；有活动任务时优先视为任务进行中。
     *
     * @param activeTask 是否存在运行或挂起中的任务
     * @param continuity 是否存在存活的任务前情
     * @return 本轮场景
     */
    public static TurnScene of(boolean activeTask, boolean continuity) {
        if (activeTask) {
            return ACTIVE_TASK;
        }
        return continuity ? CONTINUITY : FRESH;
    }

    /**
     * 本场景可选模式；指定数字员工时不允许换人。
     *
     * @param agentLocked 本轮是否已指定数字员工
     * @return 按规则匹配顺序排列的可选模式
     */
    public List<TurnMode> modes(boolean agentLocked) {
        return agentLocked ? modes.stream().filter(mode -> mode != TurnMode.SWITCH_AGENT).toList() : modes;
    }

    /**
     * 模型无有效结论时采用的模式。
     *
     * @return 兜底模式
     */
    public TurnMode fallback() {
        return fallback;
    }
}
```

### 5.2 TurnMode

```java
/**
 * 判断该模式是否需要为本轮选择数字员工。
 *
 * @return START_FRESH / START_WITH_CONTEXT / SWITCH_AGENT 为 true
 */
public boolean selectsAgent() {
    return this == START_FRESH || this == START_WITH_CONTEXT || this == SWITCH_AGENT;
}

/**
 * 宽松解析模型输出的模式名：忽略大小写与首尾空白，无法识别时返回 {@code null}。
 * <pre>{@code
 * TurnMode.of(" start_fresh ");  // START_FRESH
 * TurnMode.of("NEW_TASK");       // null
 * }</pre>
 *
 * @param raw 模型输出的模式名
 * @return 对应模式；无法识别为 {@code null}
 */
@JsonCreator
public static @Nullable TurnMode of(@Nullable String raw) {
    if (Strings.isBlank(raw)) {
        return null;
    }
    String key = raw.trim().toUpperCase(Locale.ROOT);
    return Arrays.stream(values()).filter(mode -> mode.name().equals(key)).findFirst().orElse(null);
}
```

`UNCERTAIN` 注释：有待确认的写操作、用户未显式确认时由调度器产生，意图模型不输出此值；本轮只向用户确认，不推进任务。

### 5.3 IntentResult.complete

```java
/**
 * 规范化模型结论：清理文本；只有选人模式保留 agentId，且必须来自本轮候选。
 *
 * @param candidates 本轮候选；可空
 * @return this
 */
public IntentResult complete(@Nullable List<AgentSelection> candidates) {
    useContinuity = mode == TurnMode.START_WITH_CONTEXT;
    goalSummary = Strings.blankToNull(goalSummary);
    userReply = Strings.blankToNull(userReply);
    reason = Strings.blankToNull(reason);

    boolean listed = Objects.nonNull(agentId) && Collections.isNotEmpty(candidates)
        && candidates.stream().anyMatch(candidate -> agentId.equals(candidate.getAgentId()));
    if (Objects.isNull(mode) || !mode.selectsAgent() || !listed) {
        agentId = null;
    }
    return this;
}
```

### 5.4 IntentRequest

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntentRequest(@JsonIgnore TurnScene scene, boolean agentLocked, @JsonIgnore @Nullable String userInput,
                            @Nullable Long continuityAgentId, @Nullable ContinuitySnippet continuation,
                            List<AgentSelection> agentCandidates, @Nullable DialogueWindow dialogue,
                            @Nullable String recentEpisodes, List<SkillCatalogItem> skillCatalog,
                            @Nullable ActiveTask activeTask) {
}
```

- `scene` 与 `userInput` 在提示词中有独立段落，不重复进入 JSON。
- 嵌套记录同样加 `NON_NULL`。
- 类注释中的字段说明同步更新。

### 5.5 DefaultIntentClassifier

```java
/**
 * 日志中模型原文的最大预览长度。
 */
private static final int PREVIEW_LENGTH = 200;

@Override
public @Nullable IntentResult classify(IntentRequest request) {
    try {
        String user = IntentPrompt.USER.formatted(
            request.scene().name(), JSON.obj2str(request), Objects.toString(request.userInput(), "")
        );

        ChatMessage assistant = OpenAiChatClientConverter.firstMessage(modelGateway.chat(ModelUse.INTENT,
            ChatCompletionRequests.of().system(IntentPrompt.SYSTEM.formatted(RequestLocale.tag())).user(user).build()
        ));

        // 空正文或无法解析都返回 null，由调度器按场景兜底
        String content = Objects.isNull(assistant) ? null : assistant.getContent();
        if (Strings.isBlank(content)) {
            log.warn("[IntentClassifier] empty reply, scene={}", request.scene());
            return null;
        }

        IntentResult parsed = JSON.str2obj(RuntimeUtils.extractObject(content), IntentResult.class);
        if (Objects.isNull(parsed) || Objects.isNull(parsed.getMode())) {
            log.warn("[IntentClassifier] unrecognized reply, scene={}, content={}", request.scene(),
                RuntimeUtils.abbreviate(content, PREVIEW_LENGTH));
            return null;
        }

        return parsed.complete(request.agentCandidates());
    } catch (RuntimeException ex) {
        log.warn("[IntentClassifier] classify failed, scene={}", request.scene(), ex);
        return null;
    }
}
```

### 5.6 DefaultTurnDispatcher

```java
/**
 * 意图分类读取的对话窗口条数。
 */
private static final int DIALOGUE_LIMIT = 12;

private TurnDecision decideMode(TurnContext ctx, EntryResult entry) {
    if (entry.forceContinue()) {
        audit(ctx, "protocol", null, TurnMode.CONTINUE_TASK);
        return TurnDecision.continueTask();
    }
    if (entry.agentMismatch()) {
        audit(ctx, "protocol", null, TurnMode.SWITCH_AGENT);
        return TurnDecision.switchAgent();
    }

    TurnScope turn = ctx.getTurn();
    boolean write = writePending(turn);
    TurnScene scene = TurnScene.of(hasActiveTask(turn), !turn.records().isEmpty());
    Long lockedAgentId = entry.explicitAgentId();

    IntentResult intent = intentClassifier.classify(buildIntentRequest(ctx, scene, lockedAgentId, write));
    ctx.setIntentResult(intent);
    TurnMode mode = settle(intent, scene, Objects.nonNull(lockedAgentId), write);
    audit(ctx, scene.name(), Objects.isNull(intent) ? null : intent.getMode(), mode);
    if (mode == TurnMode.UNCERTAIN) {
        // 确认追问只用固定文案，替换模型结论，避免输出模型的 userReply
        ctx.setIntentResult(confirmation(turn));
    }
    return decisionOf(mode);
}

/**
 * 待确认写操作的追问结论：回查断点询问写操作是否已完成，其余询问是否继续执行。
 *
 * @param turn 当前会话视图
 * @return 携带确认文案的 UNCERTAIN 结论
 */
private static IntentResult confirmation(TurnScope turn) {
    boolean verify = turn.checkpoint()
        .map(TaskCheckpoint::getType)
        .filter(type -> type == CheckpointType.VERIFY)
        .isPresent();
    IntentResult result = new IntentResult();
    result.setMode(TurnMode.UNCERTAIN);
    result.setUserReply((verify ? RuntimeMessage.VERIFY_ASK : RuntimeMessage.ROUTE_UNCERTAIN).text());
    return result.complete(null);
}

/**
 * 将模型结论校正为本轮生效的模式。
 *
 * <ol>
 *   <li>结论缺失或不在场景可选范围内 → 场景兜底模式
 *   <li>存在待确认写操作且判为续跑 → UNCERTAIN（写操作只由显式确认推进）
 *   <li>需要选人但无人可选、且已给出说明 → REPLY_ONLY（只回复说明，不建任务）
 * </ol>
 *
 * @param intent       模型结论；可空
 * @param scene        本轮场景
 * @param agentLocked  是否已指定数字员工
 * @param writePending 是否存在待确认的写操作
 * @return 本轮生效的模式
 */
static TurnMode settle(@Nullable IntentResult intent, TurnScene scene, boolean agentLocked, boolean writePending) {
    TurnMode mode = Optional.ofNullable(intent)
        .map(IntentResult::getMode)
        .filter(scene.modes(agentLocked)::contains)
        .orElse(scene.fallback());

    if (writePending && mode == TurnMode.CONTINUE_TASK) {
        return TurnMode.UNCERTAIN;
    }
    if (mode.selectsAgent() && Objects.nonNull(intent) && Objects.isNull(intent.getAgentId())
        && Strings.isNotBlank(intent.getUserReply())) {
        return TurnMode.REPLY_ONLY;
    }
    return mode;
}

private static TurnDecision decisionOf(TurnMode mode) {
    return switch (mode) {
        case START_FRESH -> TurnDecision.startFresh();
        case START_WITH_CONTEXT -> TurnDecision.startFollowup();
        case CONTINUE_TASK -> TurnDecision.continueTask();
        case REPLACE_TASK -> TurnDecision.replace();
        case SWITCH_AGENT -> TurnDecision.switchAgent();
        case CANCEL_TASK -> TurnDecision.cancelTask();
        case REPLY_ONLY -> TurnDecision.empty();
        case UNCERTAIN -> TurnDecision.uncertain();
    };
}

private IntentRequest buildIntentRequest(TurnContext ctx, TurnScene scene, @Nullable Long lockedAgentId,
                                         boolean writePending) {
    TurnScope turn = ctx.getTurn();
    boolean locked = Objects.nonNull(lockedAgentId);
    List<AgentCatalogItemVo> catalog = candidatesOf(lockedAgentId);
    TaskRecord latest = turn.records().isEmpty() ? null : turn.records().getFirst();
    return new IntentRequest(
        scene,
        locked,
        ctx.getRequest().getUserInput(),
        continuityAgentId(turn),
        IntentRequest.ContinuitySnippet.from(latest),
        AgentSelection.of(catalog),
        IntentRequest.DialogueWindow.from(turn.dialogue(DIALOGUE_LIMIT)),
        null,
        resolveSkillCatalog(catalog, locked),
        activeTaskOf(turn, scene == TurnScene.ACTIVE_TASK, writePending)
    );
}

/**
 * 本轮候选：未指定时为全部启用员工；指定时只保留该员工，目录未命中再单条解析。
 *
 * @param lockedAgentId 指定的员工；可空
 * @return 候选目录
 */
private List<AgentCatalogItemVo> candidatesOf(@Nullable Long lockedAgentId) {
    List<AgentCatalogItemVo> active = agentProfiles.listActiveCatalog();
    if (Objects.isNull(lockedAgentId)) {
        return active;
    }
    return active.stream()
        .filter(item -> lockedAgentId.equals(item.getAgentId()))
        .findFirst()
        .or(() -> Optional.ofNullable(agentProfiles.resolveProfile(lockedAgentId)))
        .map(List::of)
        .orElse(List.of());
}

/**
 * 判断是否存在运行或挂起中的任务。
 */
private static boolean hasActiveTask(TurnScope turn) {
    return turn.task().map(TaskMetadata::getStatus).filter(TaskStatus::isTransient).isPresent();
}

private static void audit(TurnContext ctx, String stage, @Nullable TurnMode proposed, TurnMode settled) {
    log.info("[IntentAudit] traceId={} stage={} proposed={} settled={}",
        ctx.getTurn().traceId(), stage, proposed, settled);
}
```

`resolveSkillCatalog(catalog, fullCatalog)`：`fullCatalog=true`（指定员工）时，每员工上限取总上限，保证能否处理的判断看得到该员工的全部技能。删除 `remember()`、`postValidate()`。

### 5.7 DefaultAgentResolver.route

替换原有的 `pickMode` 判断，以及"意图写了无法匹配的回复即失败"分支（该情况已由 `settle()` 转为 REPLY_ONLY）：

```java
IntentResult intent = ctx.getIntentResult();
Long picked = decision.mode().selectsAgent() && Objects.nonNull(intent) ? intent.getAgentId() : null;
if (Objects.nonNull(picked)) {
    AgentSelection byId = agentCatalog.findActiveById(picked);
    if (Objects.isNull(byId)) {
        failNoMatch(ctx, RuntimeMessage.AGENT_MISSING, TurnStatus.FAILED);
        return;
    }
    bind(ctx, decision, byId);
    return;
}
// 回退链：当前任务 Agent → 前情 Agent → 租户唯一 Agent → 无人可选（不变）
```

### 5.8 DefaultTurnEntryGuard.guard

```java
if (Objects.isNull(request.getAction())) {
    // 断点有安全默认动作（FOLLOW_UP → REPLY）时直接续跑，用户输入作为回复内容
    ResumeAction implied = resolveAction(null, checkpoint);
    if (Objects.nonNull(implied)) {
        return EntryResult.continueSuspended(agentId, focusNodeId, checkpoint, implied);
    }
    // 审批与回查没有安全默认动作：有文字交给意图判断，无文字要求显式选择
    return Strings.isNotBlank(request.getUserInput())
        ? EntryResult.accept(agentId)
        : EntryResult.reject(new RejectedReply(conversationId, taskId, RuntimeMessage.ACTION_MISSING.text()));
}
```

### 5.9 RuntimeMessage

`RuntimeMessage` 位于 `biz` 的 `i18n` 包，保存 message code 与中文兜底文案。`RuntimeMessages` 在应用启动时从 Infra 加载 `UI_MESSAGE` / `AGENT_RUNTIME` 文案；处理请求时由 `RequestLocale` 提供当前请求语言，`RuntimeMessageRegistry` 优先匹配完整 locale，再按语言回退。具名占位符在本地替换。

```java
ROUTE_UNCERTAIN("route.uncertain", "还有一项操作等待您确认，请确认是否继续执行。"),
```

只改文案。触发条件收窄为"待确认写操作 + 文字输入 + 判为续跑"，文案与之对应。

---

## 6. 验收用例

| # | 场景 | 输入 | 期望路径 | 期望结果 |
|---|---|---|---|---|
| 1 | 新对话，多员工 | "帮我开张发票" | FRESH → START_FRESH + agentId | 绑定匹配员工，建任务 |
| 2 | 新对话 | "你好" | FRESH → REPLY_ONLY | 返回模型 userReply |
| 3 | 新对话，模型返回空 | 任意 | FRESH → 兜底 START_FRESH | 走选人回退链；日志 `empty reply` |
| 4 | 新对话，指定 agentId | "帮我开张发票" | 候选仅该员工 → START_FRESH | 绑定指定员工 |
| 5 | 指定 agentId，诉求无关 | "今天天气怎样" | START_FRESH + agentId=null + userReply → REPLY_ONLY | 返回"它能办理的事项" |
| 6 | 指定 agentId，模型返回空 | 任意 | 兜底 START_FRESH | 绑定指定员工 |
| 7 | 上一任务已结束 | "那退款进度呢" | CONTINUITY → START_WITH_CONTEXT | 优先前情员工，带前情建任务 |
| 8 | FOLLOW_UP 挂起 | "上海" | 入口按 REPLY 续跑 | 不调用意图模型 |
| 9 | APPROVAL 挂起 | "好的，继续" | ACTIVE_TASK → CONTINUE_TASK → UNCERTAIN | ROUTE_UNCERTAIN 确认文案，不执行写操作 |
| 10 | APPROVAL 挂起 | "算了，不办了" | ACTIVE_TASK → CANCEL_TASK | 取消任务 |
| 11 | APPROVAL 挂起 | 点击确认（action=CONFIRM） | 入口硬协议续跑 | 执行写操作，不调用意图模型 |
| 12 | VERIFY 挂起，模型返回空 | 任意文字 | 兜底 CONTINUE_TASK → UNCERTAIN | VERIFY_ASK |
| 13 | 模型输出 `"start_fresh"` | — | `TurnMode.of` 识别 | 正常生效 |
| 14 | 模型输出 `"NEW_TASK"` | — | 解析为 null → 场景兜底 | 日志 `unrecognized reply` |
| 15 | FRESH 下模型输出 CONTINUE_TASK | — | 不在可选清单 → START_FRESH | 正常建任务 |
| 16 | 模型选了不存在的 agentId | — | `complete()` 清空 → 回退链 | 不启动不存在的员工 |
| 17 | 指定 agentId，任务进行中，要求换员工 | "换个数字员工" | SWITCH_AGENT 不在可选清单 → 兜底 | 不换人 |
| 18 | 请求 agentId 与挂起任务员工不同 | 任意 | 入口 agentMismatch → SWITCH_AGENT | 换人重开，不调用意图模型 |

---

## 7. 单元测试

| 测试类 | 覆盖 |
|---|---|
| `TurnSceneTest` | `of()` 三种场景；`modes(true)` 不含 SWITCH_AGENT；兜底值 |
| `TurnModeTest` | `of()` 大小写、空白、未知值；`selectsAgent()` |
| `IntentResultTest` | 非选人模式清空 agentId；候选外 agentId 清空；空候选清空 |
| `DefaultTurnDispatcherTest` | 用例 3、5、6、9、12、14、15、17 对应的 `settle()` 分支；UNCERTAIN 时写入确认文案并替换模型结论，VERIFY 与 APPROVAL 文案区分 |
| `DefaultTurnEntryGuardTest` | 用例 8、11、18；APPROVAL 无文字时拒绝 |
| `IntentPromptTest` | `USER.formatted(...)` 恰好三个占位符；结尾为输出指令 |

---

## 8. 关联文档更新

- `SSE_EVENTS.md`："意图不明确"一行改为新文案与触发条件（待确认写操作 + 文字输入）。
- `KNOWN_ISSUES.md`：Q11 移入"已修复"，引用本文档。
- `index.md`：在"架构"中加入本文档链接。
