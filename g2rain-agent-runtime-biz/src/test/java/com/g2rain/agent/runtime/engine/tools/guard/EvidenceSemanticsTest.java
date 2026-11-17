package com.g2rain.agent.runtime.engine.tools.guard;

import com.g2rain.agent.runtime.spec.ArgSpec;
import com.g2rain.agent.runtime.spec.EntitySpec;
import com.g2rain.agent.runtime.spec.ExtractSpec;
import com.g2rain.agent.runtime.spec.ToolSpec;
import com.g2rain.agent.runtime.spec.SideEffect;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EvidenceSemantics} 实体抽取测试。
 *
 * @author alpha
 * @since 2026/11/16
 */
class EvidenceSemanticsTest {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    private static final String TITLE = "title";

    private static final String TOKEN = "token";

    @Test
    void entitiesJson_shouldDropSensitiveArgsFromWriteEntity() {
        JsonNode args = JSON.str2node("{\"title\":\"G2rain 2.0\",\"token\":\"secret\"}");
        JsonNode payload = JSON.str2node("{\"data\":{\"id\":\"42\"}}");

        String json = EvidenceSemantics.entitiesJson(writeTool(), payload, args, true);

        JsonNode attrs = JSON.str2node(Objects.requireNonNull(json)).get(0).get("attrs");
        assertEquals("G2rain 2.0", attrs.get(TITLE).asString());
        assertFalse(attrs.has(TOKEN));
        assertTrue(args.has(TOKEN));
    }

    private static ToolSpec writeTool() {
        EntitySpec entity = new EntitySpec();
        entity.setType("article");
        ExtractSpec extract = new ExtractSpec();
        extract.setKey("article");
        extract.setPath("data");
        extract.setEntity(entity);
        ArgSpec token = new ArgSpec();
        token.setSensitive(true);
        ToolSpec tool = new ToolSpec();
        tool.setSideEffect(SideEffect.WRITE);
        tool.setExtracts(List.of(extract));
        tool.setArgs(Map.of(TITLE, new ArgSpec(), TOKEN, token));
        return tool;
    }
}
