package com.g2rain.agent.runtime.engine.tools.builtin;


import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.tools.ToolExecutionContexts;
import com.g2rain.agent.runtime.support.tool.BuiltinToolAction;
import com.g2rain.agent.runtime.support.tool.BuiltinToolCategory;
import com.g2rain.agent.runtime.support.tool.BuiltinToolKind;
import com.g2rain.agent.runtime.support.tool.BuiltinTools;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * SessionBuiltinTools（会话只读内置工具）：查询当前会话与节点现场快照。
 * <p>
 * 不改状态，只从工具执行上下文读出 conversation / node 标识与状态名。
 * </p>
 *
 * @author alpha
 * @since 2026/8/21
 */
@Component
public class SessionBuiltinTools implements BuiltinTools {

    /**
     * 查看当前会话与节点只读快照。
     *
     * <p>从 Spring 工具上下文取出本仓执行现场，读 conversation / node 标识与节点状态名。
     * 例：{@code info(toolContext)}</p>
     *
     * @param toolContext Spring AI 工具上下文，内含本节点执行现场
     * @return 会话 ID、节点 ID、节点状态名
     */
    @Tool(
        name = "builtin_session_info",
        description = """
            查看当前会话与节点状态
            """
    )
    @BuiltinToolKind(category = BuiltinToolCategory.SESSION, action = BuiltinToolAction.READ)
    public SessionInfo info(ToolContext toolContext) {
        // 只读现场，不写控流或任务记忆
        ToolExecutionContext ctx = ToolExecutionContexts.requireSpring(toolContext);
        return new SessionInfo(ctx.getConversationId(), ctx.getNodeId(), ctx.getNodeStatus().name());
    }

    /**
     * 会话与节点只读快照。
     *
     * @param conversationId 会话 ID；可空
     * @param nodeId         当前节点 ID
     * @param nodeStatus     当前节点状态名
     */
    public record SessionInfo(@Nullable String conversationId, String nodeId, String nodeStatus) {

    }
}
