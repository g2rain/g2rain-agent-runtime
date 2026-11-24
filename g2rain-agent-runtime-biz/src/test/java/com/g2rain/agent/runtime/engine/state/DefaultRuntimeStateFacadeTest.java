package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.graph.TaskGraph;
import com.g2rain.agent.runtime.domain.state.ConversationFence;
import com.g2rain.agent.runtime.domain.state.task.TaskMetadata;
import com.g2rain.agent.runtime.domain.state.task.TaskStatus;
import com.g2rain.agent.runtime.engine.state.repo.CallRepository;
import com.g2rain.agent.runtime.engine.state.repo.ConversationRepository;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.engine.store.StateStore;
import com.g2rain.agent.runtime.support.PlatformCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultRuntimeStateFacade#persist} 用例。
 *
 * @author alpha
 * @since 2026/11/22
 */
class DefaultRuntimeStateFacadeTest {

    private TurnFlushPlan flushPlan;

    private RuntimeStatePersistence persistence;

    private EntityExtractor entityExtractor;

    private DefaultRuntimeStateFacade facade;

    @BeforeEach
    void setUp() {
        flushPlan = mock(TurnFlushPlan.class);
        persistence = mock(RuntimeStatePersistence.class);
        entityExtractor = mock(EntityExtractor.class);
        facade = new DefaultRuntimeStateFacade(mock(TurnSessionHydrate.class), flushPlan, mock(EvidenceFlow.class),
            mock(StateStore.class), persistence, mock(ConversationRepository.class), mock(CallRepository.class),
            mock(PayloadRepository.class), entityExtractor, mock(PlatformCatalog.class), new RuntimeProperties());
    }

    /** W9：persist 两次都失败 → 不调用 finish。 */
    @Test
    void w9_persist_bothFailures_skipsFinish() {
        when(persistence.persistTurn(any())).thenThrow(new IllegalStateException("db down"));
        DefaultTurnScope scope = turn();

        facade.persist(scope);

        verify(persistence, times(2)).persistTurn(any());
        verify(flushPlan, never()).finish(any(), anyMap());
    }

    /** R1：第一次 persistTurn 失败、第二次成功 → collect 只调用一次。 */
    @Test
    void r1_persist_retry_collectsOnce() {
        when(persistence.persistTurn(any()))
            .thenThrow(new IllegalStateException("db down"))
            .thenReturn(Map.of());
        DefaultTurnScope scope = turn();

        facade.persist(scope);

        verify(entityExtractor, times(1)).collect(any());
        verify(persistence, times(2)).persistTurn(any());
        verify(flushPlan).finish(any(), anyMap());
    }

    /** R2：persist 一次成功 → collect 调用一次。 */
    @Test
    void r2_persist_success_collectsOnce() {
        when(persistence.persistTurn(any())).thenReturn(Map.of());
        DefaultTurnScope scope = turn();

        facade.persist(scope);

        verify(entityExtractor, times(1)).collect(any());
        verify(persistence, times(1)).persistTurn(any());
        verify(flushPlan).finish(any(), anyMap());
    }

    private static DefaultTurnScope turn() {
        TaskMetadata meta = new TaskMetadata();
        meta.setTaskId("t1");
        meta.setConversationId("c1");
        meta.setStatus(TaskStatus.RUNNING);
        ConversationFence fence = new ConversationFence("c1", "u1", "t1", 1L, "trace", System.currentTimeMillis());
        return TurnScopes.create("1", "u1", "trace", fence, List.of(), List.of(), new HashMap<>(), meta,
            new TaskGraph(), null, new HashMap<>(), null, new HashMap<>(), 60);
    }
}
