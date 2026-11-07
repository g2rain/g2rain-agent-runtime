package com.g2rain.agent.runtime.engine.flow.react;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.g2rain.agent.runtime.domain.graph.NodeStatus;
import com.g2rain.agent.runtime.domain.hydrate.ExecutionMode;
import com.g2rain.agent.runtime.domain.hydrate.OutputType;
import com.g2rain.agent.runtime.domain.hydrate.ShareScope;
import com.g2rain.agent.runtime.domain.state.SourceType;
import com.g2rain.agent.runtime.domain.state.task.CheckpointType;
import com.g2rain.agent.runtime.domain.state.task.EvidenceStatus;
import com.g2rain.agent.runtime.domain.state.task.FailureKind;
import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * ReAct 模型可见投影块的序列化模型。
 *
 * <p>各嵌套 record 是提示词的稳定数据结构，由 JSON 编解码器序列化后提供给模型；投影只
 * 暴露当前节点完成推理所需的摘要、契约、证据和恢复信息，不直接暴露底层存储对象。</p>
 *
 * @author alpha
 * @since 2026/10/3
 */
public final class ReactProjection {

    /**
     * 禁止实例化：只承载投影 record。
     */
    private ReactProjection() {

    }

    /**
     * 一块带标签的投影。
     *
     * @param tag     投影块名称
     * @param payload 将被 {@code JsonCodec} 序列化的 record / 列表
     */
    public record TaggedBlock(String tag, Object payload) {

    }

    /**
     * {@code node_goal}：当前节点的标识、目标、技能手册和平台上下文摘要。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeGoalView(String nodeId, @Nullable String objective, @Nullable String userGoal,
                               @Nullable String skillManual, @Nullable ExecutionMode mode, @Nullable String target,
                               @Nullable String phaseHint, @Nullable String delegateCatalog,
                               @Nullable String profileText, @Nullable JsonNode platform) {

    }

    /**
     * {@code input_schema}：节点输入契约以及当前已经绑定的输入值。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InputSchemaView(@Nullable List<InputFieldView> declared, @Nullable List<SourcedEntry> bound) {

    }

    /**
     * 契约声明的一项 input：名称、是否必填、来源路径与当前绑定值。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InputFieldView(String name, boolean required, @Nullable String from, @Nullable String provider,
                                 @Nullable JsonNode value, @Nullable SourceType sourceType, @Nullable Boolean missing) {

    }

    /**
     * {@code output_schema}：节点输出契约以及当前已写入的输出值。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputSchemaView(@Nullable List<OutputFieldView> declared, @Nullable List<SourcedEntry> written) {

    }

    /**
     * 契约声明的一项 output：类型、必填性、展示限制与共享范围。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputFieldView(String name, OutputType type, boolean required, @Nullable List<String> fields,
                                  @Nullable Integer minItems, @Nullable String hint, @Nullable ShareScope share,
                                  @Nullable String entityType) {

    }

    /**
     * 携带来源类型和引用路径的投影值。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourcedEntry(String name, JsonNode value, SourceType sourceType, @Nullable String ref) {

    }

    /**
     * 超长值占位：因大小限制未完整展开的值及其摘要统计。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OmittedValue(boolean omitted, int chars, @Nullable Integer items, @Nullable Integer fields) {

    }

    /**
     * {@code upstream_evidence} 一条：上游节点查询结果按抽取项给出的原文。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpstreamEvidenceView(String nodeId, String evidenceId, String tool, @Nullable String key, String path,
                                       JsonNode value) {

    }

    /**
     * {@code evidence_digest} 一条：工具证据摘要，不含可能较大的原始报文。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceItemView(String evidenceId, String tool, EvidenceStatus status,
                                   @Nullable Boolean businessOk, @Nullable Boolean empty,
                                   @Nullable List<ExtractCountView> extractCounts, FailureKind failureKind,
                                   @Nullable String argsBrief, @Nullable String resultBrief) {

    }

    /**
     * extracts 计数：某个提取字段在证据中的出现次数。
     */
    public record ExtractCountView(String key, int count) {

    }

    /**
     * {@code shared}：任务级和会话级共享值的只读投影。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SharedView(@Nullable List<SourcedEntry> task, @Nullable List<SourcedEntry> session) {

    }

    /**
     * {@code prior}：可供当前节点参考的任务前情与实体摘要。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PriorView(@Nullable String reason, @Nullable List<PriorTaskView> tasks,
                            @Nullable List<PriorEntityView> entities) {

    }

    /**
     * 任务前情里一条历史任务的最小展示视图。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PriorTaskView(String taskId, @Nullable String goal, TaskStatus status, @Nullable String summary,
                                @Nullable String request, @Nullable RejectedView rejected) {

    }

    /**
     * {@code task_plan}：任务内一个步骤；{@code current} 仅当前节点为 true。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlanStepView(@Nullable String title, NodeStatus status, @Nullable Boolean current) {

    }

    /**
     * 前情任务中被用户拒绝的待批动作。
     */
    public record RejectedView(String tool, JsonNode args) {

    }

    /**
     * 任务前情里一条实体的最小展示视图。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PriorEntityView(String key, String displayName, @Nullable String taskId) {

    }

    /**
     * 当前挂起断点的用户可见信息；不含 pending 原参数。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckpointView(@Nullable CheckpointType type, @Nullable String question,
                                 @Nullable List<ResumeAction> allowedActions, @Nullable List<String> expectedFields,
                                 @Nullable String pendingTool) {

    }

    /**
     * {@code delegate_result}：委托节点的执行结果摘要。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DelegateResultView(@Nullable String skillKey, String status, @Nullable String errorCode,
                                     @Nullable String errorMessage) {

    }

    /**
     * {@code tool_catalog} 一项：可供模型选择的工具目录项。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolItemView(String name, @Nullable Boolean builtin, @Nullable String label,
                               @Nullable Boolean confirm, @Nullable List<ArgItemView> args) {

    }

    /**
     * 工具参数路径及缺参处理提示。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArgItemView(String path, @Nullable String label, @Nullable String missingAction,
                              @Nullable String missingTool) {

    }
}
