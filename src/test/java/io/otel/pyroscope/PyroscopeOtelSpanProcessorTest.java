package io.otel.pyroscope;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

public class PyroscopeOtelSpanProcessorTest {

    @Test
    void testParseSpanId() {
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId("0"));
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId(null));
        Assertions.assertEquals(0, PyroscopeOtelSpanProcessor.parseSpanId("gggggggggggggggg"));
        Assertions.assertEquals(0xcafe, PyroscopeOtelSpanProcessor.parseSpanId("000000000000cafe"));
        Assertions.assertEquals(-4748286662364504709L, PyroscopeOtelSpanProcessor.parseSpanId("be1ab2702609dd7b"));
    }

    @Test
    void testLazyResolverNotCalledAtConstruction() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        Callable<OtelProfilerSdkBridge> resolver = () -> {
            callCount.incrementAndGet();
            return null;
        };

        PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder().build();
        new PyroscopeOtelSpanProcessor(config, resolver);

        Assertions.assertEquals(0, callCount.get(), "Resolver must not be called at construction time");
    }

    @Test
    void testLazyResolverCalledOnFirstSpan() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        OtelProfilerSdkBridge mockBridge = Mockito.mock(OtelProfilerSdkBridge.class);
        Callable<OtelProfilerSdkBridge> resolver = () -> {
            callCount.incrementAndGet();
            return mockBridge;
        };

        PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder()
                .setRootSpanOnly(false)
                .setAddSpanName(false)
                .build();
        PyroscopeOtelSpanProcessor processor = new PyroscopeOtelSpanProcessor(config, resolver);

        Assertions.assertEquals(0, callCount.get());

        ReadWriteSpan span = mockSpan("000000000000cafe");
        processor.onStart(Context.root(), span);

        Assertions.assertEquals(1, callCount.get(), "Resolver must be called on first span");
    }

    @Test
    void testLazyResolverCalledOnlyOnce() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        OtelProfilerSdkBridge mockBridge = Mockito.mock(OtelProfilerSdkBridge.class);
        Callable<OtelProfilerSdkBridge> resolver = () -> {
            callCount.incrementAndGet();
            return mockBridge;
        };

        PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder()
                .setRootSpanOnly(false)
                .setAddSpanName(false)
                .build();
        PyroscopeOtelSpanProcessor processor = new PyroscopeOtelSpanProcessor(config, resolver);

        ReadWriteSpan span = mockSpan("000000000000cafe");
        processor.onStart(Context.root(), span);
        processor.onStart(Context.root(), span);
        processor.onStart(Context.root(), span);

        Assertions.assertEquals(1, callCount.get(), "Resolver must be called exactly once across multiple spans");
    }

    @Test
    void testLazyResolverSuccessUsesBridge() throws Exception {
        OtelProfilerSdkBridge mockBridge = Mockito.mock(OtelProfilerSdkBridge.class);
        Callable<OtelProfilerSdkBridge> resolver = () -> mockBridge;

        PyroscopeOtelConfiguration config = new PyroscopeOtelConfiguration.Builder()
                .setRootSpanOnly(false)
                .setAddSpanName(false)
                .build();
        PyroscopeOtelSpanProcessor processor = new PyroscopeOtelSpanProcessor(config, resolver);

        ReadWriteSpan span = mockSpan("000000000000cafe");
        processor.onStart(Context.root(), span);

        // After lazy resolution, setTracingContext should be called via the bridge
        Mockito.verify(mockBridge, Mockito.times(1)).setTracingContext(Mockito.anyLong(), Mockito.anyLong());
    }

    // Helper to create a mock ReadWriteSpan with the given span ID.
    // rootSpanOnly=false so we don't need to set up parent span context.
    private static ReadWriteSpan mockSpan(String spanId) {
        ReadWriteSpan span = Mockito.mock(ReadWriteSpan.class);
        SpanContext spanContext = SpanContext.create(
                "00000000000000000000000000000001",
                spanId,
                TraceFlags.getDefault(),
                TraceState.getDefault()
        );
        Mockito.when(span.getSpanContext()).thenReturn(spanContext);
        Mockito.when(span.getName()).thenReturn("test-span");
        return span;
    }
}
