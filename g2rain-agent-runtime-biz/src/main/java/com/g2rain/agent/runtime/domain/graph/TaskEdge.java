package com.g2rain.agent.runtime.domain.graph;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 任务图上的一条依赖边：表示「to 节点要等 from 节点先跑完（或跳过）」。
 * <p>
 * 例：{@code from=n1, to=n2} 表示 n2 依赖 n1。
 * </p>
 * <p>边只描述两个节点之间的依赖关系，不承载节点输出值；实际交接通过上游节点的输出空间完成。</p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Getter
@Setter
@NoArgsConstructor
public class TaskEdge {

    /**
     * 上游节点 ID（先完成的一方）。
     */
    private String from;

    /**
     * 下游节点 ID（依赖 from 的一方）。
     */
    private String to;

    /**
     * 边类型；不指定时按普通依赖处理。
     */
    private EdgeType type = EdgeType.NORMAL;

    /**
     * 普通依赖边。
     *
     * @param from 上游节点 ID
     * @param to   下游节点 ID
     */
    public TaskEdge(String from, String to) {
        this.from = from;
        this.to = to;
    }

    /**
     * 指定类型的边。
     *
     * @param from 上游节点 ID
     * @param to   下游节点 ID
     * @param type 边类型
     */
    public TaskEdge(String from, String to, EdgeType type) {
        this.from = from;
        this.to = to;
        this.type = type;
    }

    /**
     * 判断该边是否表示委派关系。
     *
     * @return 类型为 {@link EdgeType#DELEGATE} 时返回 {@code true}
     */
    public boolean isDelegate() {
        return this.getType() == EdgeType.DELEGATE;
    }
}
