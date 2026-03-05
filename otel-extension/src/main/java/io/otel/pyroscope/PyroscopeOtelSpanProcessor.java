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


public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

    private final PyroscopeOtelConfiguration configuration;
    private final OtelProfilerSdkBridge profilerSdk;
    private final AsyncProfiler asprof;

    public PyroscopeOtelSpanProcessor(PyroscopeOtelConfiguration configuration, OtelProfilerSdkBridge profilerSdk) {
        this.configuration = configuration;
        this.profilerSdk = profilerSdk;
        if (profilerSdk == null) {
            asprof = PyroscopeAsyncProfiler.getAsyncProfiler();
        } else {
            asprof = null;
        }
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
        long spanName;
        long spanId = parseSpanId(strProfileId);
        if (configuration.addSpanName) {
            spanName = registerConstant(span.getName());
        } else {
            spanName = 0;
        }

        span.setAttribute(ATTRIBUTE_KEY_PROFILE_ID, strProfileId);
        setTracingContextForSpan(spanId, spanName);
    }


    @Override
    public void onEnd(ReadableSpan span) {
        setTracingContextForSpan(0, 0);
    }

    private void setTracingContextForSpan(long spanId, long spanName) {
        if (profilerSdk != null) {
            profilerSdk.setTracingContext(spanId, spanName);
        }
        if (asprof != null) {
            asprof.setTracingContext(spanId, spanName);
        }
    }

    private  long registerConstant(String name) {
        if (profilerSdk != null) {
            return profilerSdk.registerConstant(name);
        }
        return Pyroscope.LabelsWrapper.registerConstant(name);
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
