package io.otel.pyroscope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import io.pyroscope.agent.api.ProfilerApi;

import java.util.concurrent.atomic.AtomicReference;


public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

    // Default: the vendored/relocated embedded ProfilerSdk (created via ProfilerSdkFactory).
    // After shadow jar relocation, ProfilerSdkFactory becomes
    // io.otel.pyroscope.shadow.javaagent.ProfilerSdkFactory, creating the relocated ProfilerSdk.
    public static final AtomicReference<ProfilerApi> PROFILER = new AtomicReference<>(
            io.pyroscope.javaagent.ProfilerSdkFactory.create()
    );

    private final PyroscopeOtelConfiguration configuration;

    public PyroscopeOtelSpanProcessor(PyroscopeOtelConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    private ProfilerApi getProfiler() {
        // Prefer the app-classloader's ProfilerSdk set by the instrumentation hook
        // via ProfilerApi.Holder.INSTANCE. Falls back to the vendored default.
        // NOTE: the cast may throw ClassCastException when the extension CL and app CL
        // have separate ProfilerApi Class objects — this will be fixed later by ensuring
        // both classloaders share the same ProfilerApi (e.g. via bootstrap injection).
        ProfilerApi fromHolder = ProfilerApi.Holder.INSTANCE.get();
        if (fromHolder != null) {
            return fromHolder;
        }
        return PROFILER.get();
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (configuration.rootSpanOnly && !isRootSpan(span)) {
            return;
        }
        ProfilerApi api = getProfiler();
        String strProfileId = span.getSpanContext().getSpanId();
        long spanId = parseSpanId(strProfileId);
        long spanName;
        if (configuration.addSpanName) {
            spanName = api.registerConstant(span.getName());
        } else {
            spanName = 0;
        }

        span.setAttribute(ATTRIBUTE_KEY_PROFILE_ID, strProfileId);
        api.setTracingContext(spanId, spanName);
    }

    @Override
    public void onEnd(ReadableSpan span) {
        getProfiler().setTracingContext(0, 0);
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
