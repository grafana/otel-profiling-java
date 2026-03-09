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
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (configuration.rootSpanOnly && !isRootSpan(span)) {
            return;
        }
        asprof.setTracingContext(0, 0);
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

    public static boolean isRootSpan(ReadableSpan span) {
        SpanContext parent = span.getParentSpanContext();
        boolean noParent = parent == SpanContext.getInvalid();
        boolean remote = parent.isRemote();
        return remote || noParent;
    }
}
