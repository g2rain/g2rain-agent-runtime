package com.g2rain.agent.runtime.engine.state;

import com.g2rain.agent.runtime.config.RuntimeProperties;
import com.g2rain.agent.runtime.domain.state.Source;
import com.g2rain.agent.runtime.domain.state.Sourced;
import com.g2rain.agent.runtime.domain.state.task.NodeSpace;
import com.g2rain.agent.runtime.domain.state.task.SpaceSpill;
import com.g2rain.agent.runtime.engine.state.repo.PayloadRepository;
import com.g2rain.agent.runtime.spec.SourceType;
import com.g2rain.common.json.JsonCodecFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SpaceCodec} 往返用例 C5。
 *
 * @author alpha
 * @since 2026/11/22
 */
class SpaceCodecTest {

    private PayloadRepository payloads;

    private SpaceCodec codec;

    @BeforeEach
    void setUp() {
        payloads = mock(PayloadRepository.class);
        RuntimeProperties properties = new RuntimeProperties();
        properties.getState().setSpaceFieldMaxBytes(64);
        codec = new SpaceCodec(payloads, properties);
    }

    /** C5：超长字段编码为溢出占位并可还原；只有 MARK 时解码为 null。 */
    @Test
    void c5_space_spillRoundTrip_andMarkOnlyNull() {
        String longText = "x".repeat(200);
        NodeSpace space = new NodeSpace(
            Map.of(),
            Map.of("body", new Sourced<>(longText, new Source(SourceType.USER, "m1", ""), false)),
            Map.of(),
            1,
            0L,
            List.of());

        Map<String, String> encoded = codec.encode("t1", "n1", space);

        assertThat(encoded).containsKey(HashCodec.MARK);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(payloads).saveSpaceField(anyString(), eq("t1"), eq("n1"), eq("outputs"), any(), body.capture());
        SpaceSpill spill = JsonCodecFactory.instance().str2obj(encoded.get("outputs"), SpaceSpill.class);
        assertThat(spill).isNotNull();
        assertThat(spill.valid()).isTrue();

        when(payloads.loadSpaceField(spill.payloadId())).thenReturn(body.getValue());
        NodeSpace decoded = codec.decode(encoded);
        assertThat(decoded).isNotNull();
        assertThat(decoded.outputs().get("body").value()).isEqualTo(longText);

        assertThat(codec.decode(Map.of(HashCodec.MARK, ""))).isNull();
    }
}
