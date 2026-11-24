package com.g2rain.agent.runtime.domain.hydrate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ContractVersion} 兼容判断测试。
 *
 * @author alpha
 * @since 2026/11/24
 */
class ContractVersionTest {

    @Test
    void check_current_accepted() {
        assertEquals(ContractVersion.Verdict.ACCEPTED, ContractVersion.check("1.0"));
    }

    @Test
    void check_higherMinorOrMajor_ahead() {
        assertEquals(ContractVersion.Verdict.AHEAD, ContractVersion.check("1.1"));
        assertEquals(ContractVersion.Verdict.AHEAD, ContractVersion.check("2.0"));
    }

    @Test
    void check_lowerMajor_outdated() {
        assertEquals(ContractVersion.Verdict.OUTDATED, ContractVersion.check("0.9"));
    }

    @Test
    void check_illegal_invalid() {
        assertEquals(ContractVersion.Verdict.INVALID, ContractVersion.check(null));
        assertEquals(ContractVersion.Verdict.INVALID, ContractVersion.check(""));
        assertEquals(ContractVersion.Verdict.INVALID, ContractVersion.check("1"));
        assertEquals(ContractVersion.Verdict.INVALID, ContractVersion.check("1.0.0"));
        assertEquals(ContractVersion.Verdict.INVALID, ContractVersion.check("a.b"));
        assertEquals(ContractVersion.Verdict.INVALID,
            ContractVersion.check("99999999999999999999.0"));
    }
}
