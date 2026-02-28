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
    // Updated by the instrumentation hook when PyroscopeAgent.start() is called
    // in a custom classloader (e.g. Spring Boot).
    public static final AtomicReference<ProfilerApi> PROFILER = new AtomicReference<>(
            io.pyroscope.javaagent.ProfilerSdkFactory.create()
    );

    private final PyroscopeOtelConfiguration configuration;
    private volatile boolean debugPrinted = false;

    public PyroscopeOtelSpanProcessor(PyroscopeOtelConfiguration configuration) {
        this.configuration = configuration;
        System.out.println("[pyroscope-otel] SpanProcessor created. Default PROFILER: " + PROFILER.get().getClass().getName());
        System.out.println("[pyroscope-otel] SpanProcessor PROFILER classloader: " + PROFILER.get().getClass().getClassLoader());
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
        // Check if the instrumentation hook has stored a ProfilerSdk from the app classloader
        syncFromSystemProperties();
        ProfilerApi api = PROFILER.get();
        if (!debugPrinted) {
            debugPrinted = true;
            System.out.println("[pyroscope-otel] SpanProcessor.onStart: PROFILER impl: " + api.getClass().getName());
            System.out.println("[pyroscope-otel] SpanProcessor.onStart: PROFILER classloader: " + api.getClass().getClassLoader());
        }
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
        PROFILER.get().setTracingContext(0, 0);
    }

    private volatile boolean syncedFromSystemProperties = false;

    private void syncFromSystemProperties() {
        if (syncedFromSystemProperties) {
            return;
        }
        Object sdk = System.getProperties().get("io.pyroscope.otel.profilerSdk");
        if (sdk != null) {
            // The sdk object is from a different classloader (app CL), so we can't
            // directly cast it to our ProfilerApi (extension CL). Instead, create a
            // reflection-based wrapper that delegates calls via cached Method handles.
            try {
                ProfilerApi wrapper = new ReflectionProfilerApiWrapper(sdk);
                PROFILER.set(wrapper);
                syncedFromSystemProperties = true;
                System.out.println("[pyroscope-otel] SpanProcessor: Synced PROFILER from system properties (app classloader ProfilerSdk)");
                System.out.println("[pyroscope-otel] SpanProcessor: Wrapped impl: " + sdk.getClass().getName());
                System.out.println("[pyroscope-otel] SpanProcessor: Wrapped classloader: " + sdk.getClass().getClassLoader());
            } catch (Exception e) {
                System.out.println("[pyroscope-otel] SpanProcessor: Failed to create wrapper: " + e);
            }
        }
    }

    /**
     * Thin reflection wrapper around a ProfilerSdk instance from a different classloader.
     * Caches Method handles on construction for zero-lookup overhead in the hot path.
     */
    static class ReflectionProfilerApiWrapper implements ProfilerApi {
        private final Object delegate;
        private final java.lang.reflect.Method setTracingContextMethod;
        private final java.lang.reflect.Method registerConstantMethod;
        private final java.lang.reflect.Method startProfilingMethod;

        ReflectionProfilerApiWrapper(Object delegate) throws Exception {
            this.delegate = delegate;
            Class<?> cls = delegate.getClass();
            this.setTracingContextMethod = cls.getMethod("setTracingContext", long.class, long.class);
            this.registerConstantMethod = cls.getMethod("registerConstant", String.class);
            this.startProfilingMethod = cls.getMethod("startProfiling");
        }

        @Override
        public void startProfiling() {
            try {
                startProfilingMethod.invoke(delegate);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void setTracingContext(long spanId, long spanName) {
            try {
                setTracingContextMethod.invoke(delegate, spanId, spanName);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public long registerConstant(String constant) {
            try {
                return (Long) registerConstantMethod.invoke(delegate, constant);
            } catch (Exception e) {
                return 0;
            }
        }
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
