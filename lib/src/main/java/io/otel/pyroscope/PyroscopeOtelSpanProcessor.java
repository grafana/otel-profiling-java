package io.otel.pyroscope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import io.pyroscope.PyroscopeAsyncProfiler;
import io.pyroscope.labels.v2.Pyroscope;
import io.pyroscope.vendor.one.profiler.AsyncProfiler;


/**
 * Span processor for the library (non-agent) use case.
 * Calls io.pyroscope classes directly — no cross-classloader indirection needed
 * because everything runs in the same classloader.
 */
public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

    private final PyroscopeOtelConfiguration configuration;
    private final AsyncProfiler asprof;

    public PyroscopeOtelSpanProcessor() {
        this(new PyroscopeOtelConfiguration.Builder().build());
    }

    /**
     * @deprecated Use {@link #PyroscopeOtelSpanProcessor()} instead.
     */
    @Deprecated
    public PyroscopeOtelSpanProcessor(PyroscopeOtelConfiguration configuration) {
        this.configuration = configuration;
        this.asprof = PyroscopeAsyncProfiler.getAsyncProfiler();
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (configuration.rootSpanOnly && !isRootSpan(span)) {
            return;
        }
        String strProfileId = span.getSpanContext().getSpanId();
        long spanId = parseSpanId(strProfileId);
        long spanName;
        if (configuration.addSpanName) {
            spanName = Pyroscope.LabelsWrapper.registerConstant(span.getName());
        } else {
            spanName = 0;
        }

        span.setAttribute(ATTRIBUTE_KEY_PROFILE_ID, strProfileId);
        asprof.setTracingContext(spanId, spanName);
        // W3C trace ID is 32 hex chars (128 bits). Parse directly into two longs
        // to avoid the String#substring allocations on this hot path.
        String traceId = span.getSpanContext().getTraceId();
        try {
            asprof.setTraceId(parseHex64(traceId, 0), parseHex64(traceId, 16));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            asprof.setTraceId(0, 0);
        }
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (configuration.rootSpanOnly && !isRootSpan(span)) {
            return;
        }
        asprof.setTracingContext(0, 0);
        asprof.setTraceId(0L, 0L);
    }

    public static long parseSpanId(String strProfileId) {
        if (strProfileId == null || strProfileId.length() != 16) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(strProfileId, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static long parseHex64(String s, int offset) {
        long result = 0L;
        for (int i = 0; i < 16; i++) {
            int c = s.charAt(offset + i);
            int nibble;
            if (c >= '0' && c <= '9') {
                nibble = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                nibble = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                nibble = c - 'A' + 10;
            } else {
                throw new NumberFormatException("invalid hex char in trace_id at index " + (offset + i));
            }
            result = (result << 4) | nibble;
        }
        return result;
    }

    public static boolean isRootSpan(ReadableSpan span) {
        SpanContext parent = span.getParentSpanContext();
        boolean noParent = parent == SpanContext.getInvalid();
        boolean remote = parent.isRemote();
        return remote || noParent;
    }
}
