package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.agent.runtime.spec.SuccessOp;
import com.g2rain.agent.runtime.spec.SuccessSpec;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SuccessRule} 成功判定测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class SuccessRuleTest {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    private static final String SUCCESS_PATH = "code";

    private static final JsonNode BODY_WITH_CODE = JSON.str2node("{\"code\":0}");

    private static final JsonNode BODY_WITHOUT_CODE = JSON.str2node("{\"data\":{}}");

    @Test
    void conditionOf_shouldTreatMissingOpAsUnconfigured() {
        ToolSpec tool = tool(null, null);

        assertNull(SuccessRule.conditionOf(tool));
        assertFalse(SuccessRule.configured(tool));
        assertNull(SuccessRule.businessOk(tool, BODY_WITH_CODE));
    }

    @Test
    void businessOk_shouldFailNegativeOpsWhenFieldMissing() {
        assertEquals(Boolean.FALSE, SuccessRule.businessOk(tool(SuccessOp.NE, JSON.str2node("1")), BODY_WITHOUT_CODE));
        assertEquals(Boolean.FALSE, SuccessRule.businessOk(tool(SuccessOp.NOT_IN, JSON.str2node("[1]")), BODY_WITHOUT_CODE));
    }

    @Test
    void businessOk_shouldFailListOpsWhenValueIsNotArray() {
        assertEquals(Boolean.FALSE, SuccessRule.businessOk(tool(SuccessOp.NOT_IN, JSON.str2node("1")), BODY_WITH_CODE));
        assertEquals(Boolean.FALSE, SuccessRule.businessOk(tool(SuccessOp.IN, JSON.str2node("0")), BODY_WITH_CODE));
    }

    @Test
    void businessOk_shouldPassNotInWhenFieldOutsideList() {
        assertEquals(Boolean.TRUE, SuccessRule.businessOk(tool(SuccessOp.NOT_IN, JSON.str2node("[1,2]")), BODY_WITH_CODE));
    }

    private static ToolSpec tool(@Nullable SuccessOp op, @Nullable JsonNode value) {
        SuccessSpec success = new SuccessSpec();
        success.setPath(SUCCESS_PATH);
        success.setOp(op);
        success.setValue(value);
        ToolSpec tool = new ToolSpec();
        tool.setSuccess(success);
        return tool;
    }
}
