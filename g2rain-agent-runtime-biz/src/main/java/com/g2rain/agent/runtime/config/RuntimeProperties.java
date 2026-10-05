package com.g2rain.agent.runtime.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * RuntimeProperties：Agent Runtime 配置（前缀 g2rain.agent.runtime）。
 *
 * <p>Turn（本轮）= 一次用户请求从进入到产出结果的完整处理。 含模型、MCP、规划、节点循环、GoalGate 等分段。
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
@ConfigurationProperties(prefix = "g2rain.agent.runtime")
public class RuntimeProperties {

    /**
     * 模型网关客户端
     */
    private final Model model = new Model();

    /**
     * MCP Registry 客户端
     */
    private final Mcp mcp = new Mcp();

    /**
     * 规划阶段
     */
    private final Plan plan = new Plan();

    /**
     * 节点内循环（ReAct）
     */
    private final React react = new React();

    /**
     * Turn 末 GoalGate
     */
    private final GoalGate goalGate = new GoalGate();

    /**
     * 热态 TTL、窗口与运维旋钮
     */
    private final State state = new State();

    /**
     * SSE 流
     */
    private final Stream stream = new Stream();

    /**
     * 平台身份字段（BasePrincipal 内省 − hidden）
     */
    private final Platform platform = new Platform();

    /**
     * 模型调用：端点与超时、重试。
     */
    @Getter
    @Setter
    public static class Model {

        /**
         * 模型服务端点（lb:// 或 http(s)）
         */
        private String endpoint = "lb://g2rain-model-nexus";

        /**
         * 连接超时（毫秒）
         */
        private int connectTimeoutMs = 3000;

        /**
         * 读超时（毫秒）；0=不限
         */
        private int readTimeoutMs = 0;

        /**
         * 最大重试次数
         */
        private int maxRetries = 2;
    }

    /**
     * MCP 调用：Registry 端点、路径与超时、重试。
     */
    @Getter
    @Setter
    public static class Mcp {

        /**
         * MCP Registry 端点
         */
        private String endpoint = "lb://g2rain-mcp-registry";

        /**
         * MCP HTTP 路径
         */
        private String path = "/mcp";

        /**
         * 连接超时（毫秒）
         */
        private int connectTimeoutMs = 3000;

        /**
         * 读超时（毫秒）；0=近似无限
         */
        private int readTimeoutMs = 0;

        /**
         * listTools 建连失败额外重试次数（不含首次）；callTool 不重试
         */
        private int maxRetries = 2;
    }

    /**
     * 规划：候选技能上限等。
     */
    @Setter
    @Getter
    public static class Plan {

        /**
         * 候选技能最大条数
         */
        private int maxCandidates = 32;

        /**
         * 意图目录：单 Agent 写入条数上限
         */
        private int maxSkillsPerAgent = 8;

        /**
         * 意图目录：整轮技能条数上限
         */
        private int maxSkillsTotal = 48;
    }

    /**
     * 节点内循环：轮次、无进展、工作区切片与开关。
     */
    @Getter
    @Setter
    public static class React {

        /**
         * 单节点最大轮次
         */
        private int maxRounds = 32;

        /**
         * 连续无进展轮次上限
         */
        private int maxNoProgressRounds = 3;

        /**
         * 工作区切片写入提示的最大字符数
         */
        private int workspaceSliceMaxChars = 8000;

        /**
         * 投影块内单值超过此字符数则改摘要，不截半截 JSON
         */
        private int valueSummaryMaxChars = 256;

        /**
         * 补参义务最多存活轮数
         */
        private int repairMaxRounds = 8;

        /**
         * 写入 Observation 的业务错误文案上限（字符）
         */
        private int observationErrorMaxChars = 2048;

        /**
         * 工具摘要上限：流式 resultBrief、EvidenceHead.argsBrief / resultBrief（字符）
         */
        private int briefMaxChars = 200;

        /**
         * 同工具同入参连续成功次数达此值则熔断节点
         */
        private int fingerprintTripLimit = 3;
    }

    /**
     * GoalGate：开关、LLM 润色与确定性草稿字数门槛。
     */
    @Getter
    @Setter
    public static class GoalGate {

        /**
         * 是否启用 GoalGate
         */
        private boolean enabled = true;

        /**
         * 是否允许 LLM 润色
         */
        private boolean llmEnabled = true;

        /**
         * 确定性草稿达到该字数可直接采纳
         */
        private int minDeterministicChars = 24;

        /**
         * 写入模型提示时单段截断上限
         */
        private int promptMaxChars = 500;

        /**
         * 证据包节点摘要条数上限
         */
        private int nodeSummariesMax = 12;

        /**
         * 证据包对话尾取样条数
         */
        private int dialogueTail = 6;
    }

    /**
     * 热态 TTL、窗口大小和运行参数。
     */
    @Getter
    @Setter
    public static class State {

        /**
         * 会话 key TTL
         */
        private Duration conversationTtl = Duration.ofDays(7);

        /**
         * 运行中任务组 TTL
         */
        private Duration taskTtlRunning = Duration.ofHours(24);

        /**
         * 挂起任务组 TTL
         */
        private Duration taskTtlSuspended = Duration.ofHours(2);

        /**
         * 哨兵比同组其它 key 短的间隔
         */
        private Duration sentinelGap = Duration.ofMinutes(10);

        /**
         * 证据报文内联上限（字节）
         */
        private int payloadInlineMaxBytes = 524288;

        /**
         * 节点 DONE 且无委派等待时，Redis 报文 TTL；{@link Duration#ZERO} 表示直接删除 Redis 副本
         */
        private Duration payloadDoneTtl = Duration.ofHours(1);

        /**
         * EvidenceHead.entities JSON 字符上限
         */
        private int evidenceEntitiesMaxChars = 2048;

        /**
         * 是否在 flush 后打 [StateSize] 体量日志
         */
        private boolean stateSizeLogEnabled = true;

        /**
         * 节点 space 单字段上限（字节）
         */
        private int spaceFieldMaxBytes = 65536;

        /**
         * 对话单条上限（字节）
         */
        private int messageMaxBytes = 4096;

        /**
         * 会话对话保留条数
         */
        private int dialogueMax = 60;

        /**
         * 已结束任务记录保留条数
         */
        private int recordsMax = 20;

        /**
         * 实体索引上限
         */
        private int entitiesMax = 200;

        /**
         * Prior 最多带入的任务记录数
         */
        private int priorTasksMax = 5;

        /**
         * Prior 最多带入的实体数
         */
        private int priorEntitiesMax = 10;

        /**
         * 单次 flush JSON 上限（字节）
         */
        private int flushMaxBytes = 1048576;

        /**
         * APPROVAL 超过此时长须重新查询再确认
         */
        private Duration reconfirmAfter = Duration.ofHours(24);

        /**
         * 委派深度上限
         */
        private int delegateMaxDepth = 2;

        /**
         * 允许契约显式 confirm=false 的 WRITE 工具白名单
         */
        private Set<String> writeConfirmBypassTools = new LinkedHashSet<>();
    }

    /**
     * SSE：超时、心跳、活动合并窗口。
     */
    @Getter
    @Setter
    public static class Stream {

        /**
         * 单条 SSE 流最长存活；0 = 不超时，由收口 complete
         */
        private Duration timeout = Duration.ZERO;

        /**
         * 无其它事件时心跳间隔
         */
        private Duration pingInterval = Duration.ofSeconds(15);
    }

    /**
     * 平台身份：从 {@code BasePrincipal} 内省，hidden 不可见。
     */
    @Getter
    @Setter
    public static class Platform {

        /**
         * 不进入 prompt / from / PLATFORM 校验的属性名
         */
        private Set<String> hidden = new LinkedHashSet<>(Set.of(
            "passportId", "clientId", "sessionType", "adminUser", "adminCompany"
        ));
    }
}
