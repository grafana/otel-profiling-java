package io.otel.pyroscope;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PyroscopeOtelSpanProcessorTest {

    @Mock
    OtelProfilerSdkBridge profilerSdk;

    @Test
    void testParseSpanId() {
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId("0"));
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId(null));
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId("gggggggggggggggg"));
        Assertions.assertEquals(0xcafe, PyroscopeOtelSpanProcessor.parseSpanId("000000000000cafe"));
        Assertions.assertEquals(-4748286662364504709L, PyroscopeOtelSpanProcessor.parseSpanId("be1ab2702609dd7b"));
    }

    @Test
    void testOnEndRootSpanOnly_childSpanDoesNotClearContext() {
        PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder()
                .setRootSpanOnly(true)
                .build();
        PyroscopeOtelSpanProcessor processor = new PyroscopeOtelSpanProcessor(config, profilerSdk);

        ReadableSpan childSpan = createMockSpan(false);
        processor.onEnd(childSpan);

        verify(profilerSdk, never()).setTracingContext(anyLong(), anyLong());
    }

    @Test
    void testOnEndRootSpanOnly_rootSpanClearsContext() {
        PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder()
                .setRootSpanOnly(true)
                .build();
        PyroscopeOtelSpanProcessor processor = new PyroscopeOtelSpanProcessor(config, profilerSdk);

        ReadableSpan rootSpan = createMockSpan(true);
        processor.onEnd(rootSpan);

        verify(profilerSdk).setTracingContext(0, 0);
    }

    @Test
    void testOnEndAllSpans_childSpanClearsContext() {
        PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder()
                .setRootSpanOnly(false)
                .build();
        PyroscopeOtelSpanProcessor processor = new PyroscopeOtelSpanProcessor(config, profilerSdk);

        ReadableSpan childSpan = org.mockito.Mockito.mock(ReadableSpan.class);
        processor.onEnd(childSpan);

        verify(profilerSdk).setTracingContext(0, 0);
    }

    @Test
    void testOnStartRootSpanOnly_childSpanIgnored() {
        PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder()
                .setRootSpanOnly(true)
                .build();
        PyroscopeOtelSpanProcessor processor = new PyroscopeOtelSpanProcessor(config, profilerSdk);

        ReadWriteSpan childSpan = createMockReadWriteSpan(false);
        processor.onStart(Context.root(), childSpan);

        verify(profilerSdk, never()).setTracingContext(anyLong(), anyLong());
    }

    private static ReadableSpan createMockSpan(boolean root) {
        ReadableSpan span = org.mockito.Mockito.mock(ReadableSpan.class);
        if (root) {
            when(span.getParentSpanContext()).thenReturn(SpanContext.getInvalid());
        } else {
            SpanContext parentContext = SpanContext.create(
                    TraceId.fromLongs(1, 2),
                    SpanId.fromLong(3),
                    TraceFlags.getDefault(),
                    TraceState.getDefault()
            );
            when(span.getParentSpanContext()).thenReturn(parentContext);
        }
        return span;
    }

    private static ReadWriteSpan createMockReadWriteSpan(boolean root) {
        ReadWriteSpan span = org.mockito.Mockito.mock(ReadWriteSpan.class);
        if (root) {
            when(span.getParentSpanContext()).thenReturn(SpanContext.getInvalid());
        } else {
            SpanContext parentContext = SpanContext.create(
                    TraceId.fromLongs(1, 2),
                    SpanId.fromLong(3),
                    TraceFlags.getDefault(),
                    TraceState.getDefault()
            );
            when(span.getParentSpanContext()).thenReturn(parentContext);
        }
        return span;
    }
}
