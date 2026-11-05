package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.agent.runtime.domain.graph.TaskNode;
import com.g2rain.agent.runtime.domain.hydrate.ToolSpec;
import com.g2rain.agent.runtime.domain.state.task.SideEffect;
import com.g2rain.agent.runtime.domain.tool.Observation;
import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.domain.tool.ToolInvokeRequest;
import com.g2rain.agent.runtime.engine.tools.ToolInterceptor;
import com.g2rain.agent.runtime.enums.ModelHint;
import com.g2rain.agent.runtime.enums.RuntimeErrorCode;
import com.g2rain.agent.runtime.support.ToolCallbacks;
import com.g2rain.common.utils.Strings;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * 写范围门：计划分给其他节点的写操作，本节点不得抢先执行。
 *
 * <p>写工具是本节点的目标时放行；是图中另一个未结束节点的目标时拒绝；其余写调用放行，照常认领、确认与查重。
 * 排在参数绑定与确认闸之前，被拒的调用不做绑定、不申请确认。
 * 例：查询文章的节点调用 {@code post_tag_save}，而它是「创建标签」节点的目标，返回 {@code WRITE_OUT_OF_SCOPE}。</p>
 *
 * @author alpha
 * @since 2026/11/5
 */
@Order(0)
@Component
public class WriteScopeInterceptor implements ToolInterceptor {

    /**
     * 抢先执行其他节点的完成目标时短路为拒绝观测；其余放行。
     *
     * @param request 本次工具调用请求
     * @return 越界时的拒绝观测；否则为空
     */
    @Override
    public Optional<Observation> before(ToolInvokeRequest request) {
        if (ToolCallbacks.isBuiltin(request.toolCallback())) {
            return Optional.empty();
        }

        ToolSpec contract = request.contract();
        if (Objects.isNull(contract) || contract.sideEffectOrDefault() != SideEffect.WRITE) {
            return Optional.empty();
        }

        ToolExecutionContext context = request.context();
        String toolName = request.toolName();
        // 本节点的目标照常执行；多个节点同以该工具为目标时，各写各的
        if (toolName.equals(context.getTarget())) {
            return Optional.empty();
        }

        TaskNode owner = context.getGraph().targetOwner(toolName, context.getNodeId());
        if (Objects.isNull(owner)) {
            return Optional.empty();
        }

        return Optional.of(Observation.rejected(
            RuntimeErrorCode.WRITE_OUT_OF_SCOPE,
            ModelHint.WRITE_OUT_OF_SCOPE.text("tool", toolName, "node", Strings.defaultIfBlank(owner.getTitle(), owner.getNodeId())),
            ModelHint.NEXT_FINISH_SCOPE.text()
        ));
    }
}
