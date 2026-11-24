package com.g2rain.agent.runtime.support.tool;

import com.g2rain.agent.runtime.domain.tool.ToolExecutionContext;
import com.g2rain.agent.runtime.engine.state.RuntimeStateFacade;
import com.g2rain.agent.runtime.engine.tools.guard.EvidenceReader;
import com.g2rain.agent.runtime.spi.Attachment;
import com.g2rain.agent.runtime.spi.CapabilityContext;
import com.g2rain.agent.runtime.spi.EvidenceView;
import com.g2rain.agent.runtime.support.RequestLocale;
import com.g2rain.common.utils.Strings;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 由 {@link ToolExecutionContext} 适配的能力调用现场。
 *
 * <p>证据经 {@link EvidenceReader} 读取本节点与上游已成功的调用，按发生顺序给出；
 * {@link #payload} 复用按 id 读报文的方式，不可达时为空。</p>
 *
 * @author alpha
 * @since 2026/11/24
 */
public final class ToolCapabilityContext implements CapabilityContext {

    /**
     * 工具执行上下文。
     */
    private final ToolExecutionContext execution;

    /**
     * 证据读取器。
     */
    private final EvidenceReader evidenceReader;

    /**
     * 状态门面：按 id 读证据报文。
     */
    private final RuntimeStateFacade stateFacade;

    /**
     * @param execution      工具执行上下文
     * @param evidenceReader 证据读取器
     * @param stateFacade    状态门面
     */
    public ToolCapabilityContext(ToolExecutionContext execution, EvidenceReader evidenceReader,
                                 RuntimeStateFacade stateFacade) {
        this.execution = Objects.requireNonNull(execution);
        this.evidenceReader = Objects.requireNonNull(evidenceReader);
        this.stateFacade = Objects.requireNonNull(stateFacade);
    }

    @Override
    public String organId() {
        return execution.scope().turn().organId();
    }

    @Override
    public String userId() {
        return execution.scope().turn().userId();
    }

    @Override
    public String conversationId() {
        return execution.getConversationId();
    }

    @Override
    public String taskId() {
        return execution.getTaskId();
    }

    @Override
    public String nodeId() {
        return execution.getNodeId();
    }

    @Override
    public @Nullable String skillKey() {
        return execution.getSkillKey();
    }

    @Override
    public String locale() {
        return RequestLocale.tag();
    }

    @Override
    public List<EvidenceView> evidence() {
        // 读取器给出新的在前；翻转为发生顺序（旧的在前）
        List<EvidenceReader.OwnedEvidence> chronological =
            new ArrayList<>(evidenceReader.successfulEvidenceNewestFirst(execution));
        Collections.reverse(chronological);
        List<EvidenceView> views = new ArrayList<>(chronological.size());
        for (EvidenceReader.OwnedEvidence owned : chronological) {
            views.add(new EvidenceView(
                owned.head().evidenceId(),
                owned.ownerNodeId(),
                owned.head().toolName(),
                owned.head().round(),
                Boolean.TRUE.equals(owned.head().empty())
            ));
        }
        return Collections.unmodifiableList(views);
    }

    @Override
    public Optional<JsonNode> payload(String evidenceId) {
        // 空白或不可达：不属于本任务，不读报文
        if (Strings.isBlank(evidenceId) || !evidenceReader.reachable(execution, evidenceId.trim())) {
            return Optional.empty();
        }

        JsonNode payload = stateFacade.payload(execution.getFrame().turn(), execution.getTaskId(), evidenceId.trim());
        if (payload.isNull() || payload.isMissingNode()) {
            return Optional.empty();
        }

        return Optional.of(payload);
    }

    @Override
    public void attach(Attachment attachment) {
        execution.scope().turn().attach(attachment);
    }
}
