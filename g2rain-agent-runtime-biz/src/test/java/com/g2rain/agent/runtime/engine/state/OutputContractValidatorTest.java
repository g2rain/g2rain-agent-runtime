package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.hydrate.SkillContract;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.agent.runtime.engine.tools.ToolRejectedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link OutputContractValidator} 产出来源校验测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class OutputContractValidatorTest {

    private static final String OUTPUT_KEY = "article_id";

    private static final String VALUE = "42";

    private final OutputContractValidator validator = new OutputContractValidator();

    @Test
    void requireAcceptedSource_shouldRejectContractSourceWhenOutputsUndeclared() {
        Source source = new Source(SourceType.CONTRACT, "contract", "");

        assertThrows(ToolRejectedException.class,
            () -> validator.requireAcceptedSource(new SkillContract(), OUTPUT_KEY, source, VALUE));
    }

    @Test
    void requireAcceptedSource_shouldLeaveEvidenceSourceToVerifierWhenOutputsUndeclared() {
        Source source = new Source(SourceType.EVIDENCE, "ev-1", "data.id");

        assertDoesNotThrow(() -> validator.requireAcceptedSource(new SkillContract(), OUTPUT_KEY, source, VALUE));
    }
}
