package io.otel.pyroscope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import io.pyroscope.agent.api.ProfilerApi;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;


public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

    // Default: the vendored/relocated embedded ProfilerSdk (created via ProfilerSdkFactory).
    // After shadow jar relocation, ProfilerSdkFactory becomes
    // io.otel.pyroscope.shadow.javaagent.ProfilerSdkFactory, creating the relocated ProfilerSdk.
    //
    // Updated when the instrumentation hook detects PyroscopeAgent.start() in a custom
    // classloader (e.g. Spring Boot) — the hook sets the app-CL's ProfilerApi.Holder.INSTANCE,
    // and this span processor picks it up via syncFromAppClassLoader().
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
        // Check if the instrumentation hook has set a ProfilerSdk from the app classloader
        syncFromAppClassLoader();
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

    private volatile boolean syncedFromAppClassLoader = false;

    /**
     * Picks up the ProfilerSdk instance that the instrumentation advice stored in the
     * app classloader's ProfilerApi.Holder.INSTANCE.
     *
     * Why we can't use ProfilerApi.Holder.INSTANCE directly:
     * The extension CL and the app CL each have their own copy of ProfilerApi (helper
     * injection creates a separate Class object per classloader). A direct cast like
     * {@code (ProfilerApi) appClSdk} throws ClassCastException because the JVM sees two
     * unrelated ProfilerApi types. We therefore wrap the cross-CL object in a
     * {@link ReflectionProfilerApiWrapper} that delegates via cached Method handles —
     * this is the standard pattern for bridging classloader boundaries without a shared
     * parent classloader.
     */
    private void syncFromAppClassLoader() {
        if (syncedFromAppClassLoader) {
            return;
        }
        // The app CL's ProfilerApi.Holder.INSTANCE was set by our advice.
        // We find it by looking at all classloaders that have loaded our helper class.
        // The advice sets the value on the copy of ProfilerApi injected into the app CL.
        // We can find it via the thread context classloader (which is typically the app CL).
        try {
            ClassLoader appCL = Thread.currentThread().getContextClassLoader();
            if (appCL == null) {
                return;
            }
            // Constructed at runtime to avoid shadow relocation
            String holderClassName = String.join(".", "io", "pyroscope", "agent", "api") + ".ProfilerApi$Holder";
            Class<?> holderClass = appCL.loadClass(holderClassName);
            Field instanceField = holderClass.getField("INSTANCE");
            @SuppressWarnings("unchecked")
            AtomicReference<Object> holder = (AtomicReference<Object>) instanceField.get(null);
            Object sdk = holder.get();
            if (sdk == null) {
                return;
            }
            ProfilerApi wrapper = new ReflectionProfilerApiWrapper(sdk);
            PROFILER.set(wrapper);
            syncedFromAppClassLoader = true;
            System.out.println("[pyroscope-otel] SpanProcessor: Synced PROFILER from app classloader's ProfilerApi.Holder");
            System.out.println("[pyroscope-otel] SpanProcessor: Wrapped impl: " + sdk.getClass().getName());
            System.out.println("[pyroscope-otel] SpanProcessor: Wrapped classloader: " + sdk.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            // ProfilerApi not yet loaded in app CL — will try again on next span
        } catch (Exception e) {
            System.out.println("[pyroscope-otel] SpanProcessor: Failed to sync from app classloader: " + e);
        }
    }

    /**
     * Bridges a ProfilerSdk loaded by the app classloader into the extension classloader's
     * ProfilerApi interface. Required because the two classloaders have separate ProfilerApi
     * Class objects — a direct cast is impossible. Method handles are cached at construction
     * time so the hot path (setTracingContext, registerConstant) has no reflection lookup cost.
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
