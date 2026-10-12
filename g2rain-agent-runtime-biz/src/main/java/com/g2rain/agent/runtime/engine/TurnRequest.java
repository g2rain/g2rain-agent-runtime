package com.g2rain.agent.runtime.engine;


import com.g2rain.agent.runtime.domain.state.task.ResumeAction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * 进入会话编排入口的一次用户请求。
 * <p>
 * 经 {@code POST /v1/chat/completions} 等入口传入：标明渠道、可选指定 Agent / 会话，
 * 以及本轮用户原文或挂起恢复动作。活跃任务以会话栅栏 {@code activeTaskId} 为准，请求不带 taskId。
 * </p>
 * <p>
 * 例：首聊只带 {@code channel} + {@code userInput}，服务端生成 conversationId；
 * 续聊带上已有 conversationId；从挂起恢复时可带 {@code action=CONFIRM} 与补充 {@code userInput}。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "本轮请求：渠道、会话/任务定位、用户输入与挂起恢复动作")
public final class TurnRequest {

    /**
     * 请求渠道标识。
     * <p>用于区分来源端，例：{@code web}。</p>
     */
    @NotBlank
    @Schema(description = "请求渠道标识", example = "web", requiredMode = Schema.RequiredMode.REQUIRED)
    private String channel;

    /**
     * 显式指定的 Agent 主键。
     * <p>为空时由路由/意图决策选定 Agent；非空时可强制绑定或参与换 Agent 判定。</p>
     */
    @Nullable
    @Schema(description = "显式指定的 Agent 主键；为空则由路由决策", example = "1001")
    private Long agentId;

    /**
     * 本轮选用的模型名；为空时由模型服务按默认路由。
     */
    @Nullable
    @Schema(description = "本轮选用的模型名；为空时由模型服务按默认路由", example = "qwen3-32b")
    private String model;

    /**
     * 会话 ID。
     * <p>为空表示新会话，服务端会生成并落会话骨架；续聊/恢复时传入已有 id。</p>
     */
    @Schema(description = "会话 ID；为空表示新建会话", example = "abc")
    private String conversationId;

    /**
     * 本轮用户原文输入。
     * <p>新开任务或追问回复时填写；纯确认/拒绝等动作场景可为空。</p>
     */
    @Nullable
    @Schema(description = "本轮用户原文；追问回复或新开任务时填写", example = "帮我查一下订单 ORD-10086 的物流")
    private String userInput;

    /**
     * 挂起恢复动作；为空表示本轮不是结构化恢复请求。
     * <p>
     * 节点挂起后用户选择如何继续：{@link ResumeAction#REPLY} 文字回复、
     * {@link ResumeAction#CONFIRM} 确认、{@link ResumeAction#REJECT} 拒绝、
     * {@link ResumeAction#CANCEL} 取消。非恢复场景为 {@code null}。
     * </p>
     */
    @Nullable
    @Schema(description = "挂起恢复动作：REPLY / CONFIRM / REJECT / CANCEL；非恢复场景为空", example = "REPLY", allowableValues = {"REPLY", "CONFIRM", "REJECT", "CANCEL"})
    private ResumeAction action;
}
