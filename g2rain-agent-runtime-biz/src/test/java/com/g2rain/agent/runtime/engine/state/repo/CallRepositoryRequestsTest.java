package com.g2rain.agent.runtime.engine.state.repo;

import com.g2rain.agent.runtime.service.ToolCallRecordService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link CallRepository#requests} 用例 E11。
 *
 * @author alpha
 * @since 2026/11/22
 */
class CallRepositoryRequestsTest {

    /** E11：callIds 为空 → 空 Map，不调 Service。 */
    @Test
    void e11_requests_emptyCallIds_returnsEmptyWithoutService() {
        ToolCallRecordService service = mock(ToolCallRecordService.class);
        CallRepository repository = new CallRepository(service);

        Map<String, String> result = repository.requests("t1", List.of());

        assertThat(result).isEmpty();
        verify(service, never()).selectRequests(any(), any());
    }
}
