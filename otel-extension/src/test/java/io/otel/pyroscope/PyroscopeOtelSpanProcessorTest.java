package io.otel.pyroscope;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PyroscopeOtelSpanProcessorTest {

    @Test
    void testParseSpanId() {
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId("0"));
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId(null));
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId("gggggggggggggggg"));
        Assertions.assertEquals(0xcafe, PyroscopeOtelSpanProcessor.parseSpanId("000000000000cafe"));
        Assertions.assertEquals(-4748286662364504709L, PyroscopeOtelSpanProcessor.parseSpanId("be1ab2702609dd7b"));
    }
}
