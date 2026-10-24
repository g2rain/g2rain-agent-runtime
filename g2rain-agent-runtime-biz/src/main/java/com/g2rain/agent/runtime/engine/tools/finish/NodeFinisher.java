package com.g2rain.agent.runtime.engine.tools.finish;

import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.EntityExtractor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.support.trace.Trace;
import com.g2rain.agent.runtime.support.trace.TraceStage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 节点收口：先过 {@link FinishPolicy}，通过后落产出并沉淀实体。
 *
 * <p>模型调用 {@code builtin_node_finish} 与工具契约 {@code returnDirect} 命中共用这一处，
 * 两条路径的完成规则一致。门槛未过时只记拒绝原因，节点仍可继续循环。</p>
 *
 * @author alpha
 * @since 2026/10/24
 */
@Component
@RequiredArgsConstructor
public class NodeFinisher {

    /**
     * finish 门槛（必填产出、节点完成目标等）。
     */
    private final FinishPolicy finishPolicy;

    /**
     * 完成后沉淀 output 实体候选与 share。
     */
    private final EntityExtractor entityExtractor;

    /**
     * 尝试完成节点。
     *
     * @param ctx    节点执行现场
     * @param output 节点正式产出
     * @return 已完成为 {@code true}；被拒为 {@code false}，原因见 {@link ToolExecutionContext#getFinishRejectedReason()}
     */
    public boolean finish(ToolExecutionContext ctx, Object output) {
        FinishDecision decision = finishPolicy.evaluate(new FinishContext(output, ctx));
        if (!decision.allowed()) {
            ctx.applyFinishRejected(Objects.requireNonNullElse(decision.reason(), ModelHint.FINISH_REJECTED.text()));
            return false;
        }

        ctx.applyFinish(output);
        entityExtractor.captureOutputs(ctx.scope(), ctx.contract());
        // 技能有写工具、节点却无完成目标就结束：观察规划漏填 target 的频率
        if (Objects.isNull(ctx.getTarget()) && ctx.hasWriteTools()) {
            Trace.at(TraceStage.NODE, "finished_without_target")
                .kv("nodeId", ctx.getNodeId())
                .kv("skillKey", ctx.getSkillKey())
                .info();
        }
        return true;
    }
}
