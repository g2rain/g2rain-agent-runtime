package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.domain.state.progress.ProgressStep;
import com.g2rain.agent.runtime.dto.ChatProgressDto;
import com.g2rain.agent.runtime.service.ChatProgressService;
import com.g2rain.agent.runtime.support.RuntimeUtils;
import com.g2rain.common.utils.Collections;
import com.g2rain.common.web.PrincipalContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 执行过程步骤仓储（{@code chat_progress}）。
 */
@Component
@RequiredArgsConstructor
public class ProgressRepository {

    /**
     * {@code chat_progress.title} 上限。
     */
    private static final int TITLE_MAX = 256;

    /**
     * {@code chat_progress.detail} 上限。
     */
    private static final int DETAIL_MAX = 1000;

    /**
     * 执行过程步骤表服务。
     */
    private final ChatProgressService chatProgressService;

    /**
     * 批量写入本轮步骤。
     *
     * @param conversationId 会话 id
     * @param traceId        本轮追踪号
     * @param steps          本轮步骤；空则跳过
     */
    public void insert(String conversationId, String traceId, List<ProgressStep> steps) {
        if (Collections.isEmpty(steps)) {
            return;
        }

        Long organId = PrincipalContextHolder.getOrganId();
        List<ChatProgressDto> rows = steps.stream().map(step -> {
            ChatProgressDto dto = new ChatProgressDto();
            dto.setOrganId(organId);
            dto.setConversationId(conversationId);
            dto.setTraceId(traceId);
            dto.setTaskId(step.getTaskId());
            dto.setStepId(step.getStepId());
            dto.setParentStepId(step.getParentStepId());
            dto.setSeq(step.getSeq());
            dto.setStage(step.getStage().name());
            dto.setStatus(step.getStatus().name());
            dto.setTitle(RuntimeUtils.clip(step.getTitle(), TITLE_MAX));
            dto.setDetail(RuntimeUtils.clip(step.getDetail(), DETAIL_MAX));
            dto.setNodeId(step.getNodeId());
            dto.setRound(step.getRound());
            dto.setErrorCode(step.getErrorCode());
            dto.setExtra(RepoSupport.toJson(step.getExtra()));
            dto.setStartedAtMs(step.getStartedAt());
            dto.setElapsedMs(step.getElapsedMs());
            return dto;
        }).toList();
        chatProgressService.saveBatch(rows);
    }
}
