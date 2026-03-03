package io.otel.pyroscope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerApiHolder;


public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

    static {
        // Initialize with the vendored/relocated embedded ProfilerSdk as the default.
        // After shadow jar relocation, ProfilerSdkFactory becomes
        // io.otel.pyroscope.shadow.javaagent.ProfilerSdkFactory, creating the relocated ProfilerSdk.
        // This may be replaced later by the app-classloader's ProfilerSdk via:
        // - tryLoadFromSystemClassLoader() in AutoConfig, or
        // - ProfilerSdkInstrumentation hooking PyroscopeAgent.start()
        ProfilerApiHolder.INSTANCE.compareAndSet(null, io.pyroscope.javaagent.ProfilerSdkFactory.create());
    }

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
        return ProfilerApiHolder.INSTANCE.get();
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
