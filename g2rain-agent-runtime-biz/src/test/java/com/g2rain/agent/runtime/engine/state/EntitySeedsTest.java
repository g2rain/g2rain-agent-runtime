package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.domain.state.task.EntitySeed;
import com.g2rain.agent.runtime.spec.EntitySpec;
import com.g2rain.common.json.JsonCodec;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link EntitySeeds} 抽取行为。
 *
 * @author alpha
 * @since 2026/11/17
 */
class EntitySeedsTest {

    private static final JsonCodec JSON = JsonCodecFactory.instance();

    @Test
    void of_shouldTakeIdAndNameFromObjectFields() {
        EntitySpec spec = articleSpec();
        ObjectNode base = JsonNodeFactory.instance.objectNode();
        JsonNode item = JSON.str2node("{\"id\":\"42\",\"name\":\"G2rain\",\"title\":\"x\"}");

        EntitySeed seed = EntitySeeds.of(spec, base, item);

        assertNotNull(seed);
        assertEquals("article", seed.type());
        assertEquals("42", seed.id());
        assertEquals("G2rain", seed.displayName());
        assertEquals("x", seed.attrs().get("title").asString());
    }

    @Test
    void of_shouldNameScalarThenTakeId() {
        EntitySpec spec = articleSpec();
        spec.setScalar("id");
        ObjectNode base = JsonNodeFactory.instance.objectNode();
        JsonNode item = JSON.str2node("\"99\"");

        EntitySeed seed = EntitySeeds.of(spec, base, item);

        assertNotNull(seed);
        assertEquals("99", seed.id());
        assertEquals("99", seed.displayName());
    }

    @Test
    void of_shouldReturnNullWhenIdMissing() {
        EntitySpec spec = articleSpec();
        ObjectNode base = JsonNodeFactory.instance.objectNode();
        JsonNode item = JSON.str2node("{\"name\":\"no-id\"}");

        assertNull(EntitySeeds.of(spec, base, item));
    }

    private static EntitySpec articleSpec() {
        EntitySpec spec = new EntitySpec();
        spec.setType("article");
        return spec;
    }
}
