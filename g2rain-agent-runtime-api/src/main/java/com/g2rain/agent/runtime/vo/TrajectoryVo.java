package com.g2rain.agent.runtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话执行轨迹：任务、节点、工具调用与拦截，供可观测与调试读取。
 *
 * @author alpha
 * @since 2026/11/23
 */
@Setter
@Getter
@NoArgsConstructor
@Schema(description = "会话执行轨迹")
public class TrajectoryVo {

    /**
     * 会话标识。
     */
    @Schema(description = "会话标识")
    private String conversationId;

    /**
     * 该会话下的任务（按创建时间升序）。
     */
    @Schema(description = "任务列表")
    private List<Task> tasks = new ArrayList<>();

    /**
     * 该会话全部任务的工具调用（按创建时间、主键升序）。
     */
    @Schema(description = "工具调用列表")
    private List<Call> calls = new ArrayList<>();

    /**
     * 该会话全部任务的工具拦截（按创建时间、主键升序）。
     */
    @Schema(description = "工具拦截列表")
    private List<Block> blocks = new ArrayList<>();

    /**
     * 任务摘要及其节点。
     */
    @Setter
    @Getter
    @NoArgsConstructor
    @Schema(description = "轨迹中的任务")
    public static class Task {

        @Schema(description = "任务业务标识")
        private String taskId;

        @Schema(description = "数字员工主键")
        private Long agentId;

        @Schema(description = "任务状态")
        private String status;

        @Schema(description = "开任务入站追踪号")
        private String traceId;

        @Schema(description = "开始时间")
        private LocalDateTime startedTime;

        @Schema(description = "结束时间")
        private LocalDateTime finishedTime;

        @Schema(description = "节点列表")
        private List<Node> nodes = new ArrayList<>();
    }

    /**
     * 任务图节点摘要。
     */
    @Setter
    @Getter
    @NoArgsConstructor
    @Schema(description = "轨迹中的节点")
    public static class Node {

        @Schema(description = "节点业务标识")
        private String nodeId;

        @Schema(description = "节点标题")
        private String title;

        @Schema(description = "技能键")
        private String skillKey;

        @Schema(description = "节点状态")
        private String status;

        @Schema(description = "错误码")
        private String errorCode;
    }

    /**
     * 工具调用摘要（不含响应原文）。
     */
    @Setter
    @Getter
    @NoArgsConstructor
    @Schema(description = "轨迹中的工具调用")
    public static class Call {

        @Schema(description = "调用业务标识")
        private String callId;

        @Schema(description = "所属任务")
        private String taskId;

        @Schema(description = "所属节点")
        private String nodeId;

        @Schema(description = "ReAct 轮次")
        private Integer round;

        @Schema(description = "工具名")
        private String toolName;

        @Schema(description = "副作用")
        private String sideEffect;

        @Schema(description = "请求内容（已脱敏）")
        private String request;

        @Schema(description = "调用状态")
        private String callStatus;

        @Schema(description = "业务是否成功")
        private Byte businessOk;

        @Schema(description = "是否空结果")
        private Byte emptyResult;

        @Schema(description = "错误码")
        private String errorCode;

        @Schema(description = "错误信息")
        private String errorMessage;

        @Schema(description = "耗时毫秒")
        private Long durationMs;

        @Schema(description = "追踪号")
        private String traceId;

        @Schema(description = "创建时间")
        private LocalDateTime createTime;
    }

    /**
     * 工具拦截摘要。
     */
    @Setter
    @Getter
    @NoArgsConstructor
    @Schema(description = "轨迹中的工具拦截")
    public static class Block {

        @Schema(description = "调用标识")
        private String callId;

        @Schema(description = "所属任务")
        private String taskId;

        @Schema(description = "所属节点")
        private String nodeId;

        @Schema(description = "ReAct 轮次")
        private Integer round;

        @Schema(description = "所属技能")
        private String skillKey;

        @Schema(description = "工具名")
        private String toolName;

        @Schema(description = "拦下调用的拦截器")
        private String blockedBy;

        @Schema(description = "错误码")
        private String errorCode;

        @Schema(description = "原因编码")
        private String reasonCode;

        @Schema(description = "拒绝说明")
        private String message;

        @Schema(description = "入参摘要（已脱敏）")
        private String args;

        @Schema(description = "追踪号")
        private String traceId;

        @Schema(description = "创建时间")
        private LocalDateTime createTime;
    }
}
