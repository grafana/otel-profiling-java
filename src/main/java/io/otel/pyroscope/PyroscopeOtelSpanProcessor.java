package io.otel.pyroscope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerApiHolder;

import java.lang.reflect.Constructor;


public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

    /**
     * Whether we've already attempted to load ProfilerSdk from the thread context classloader.
     * Once set to true, we stop trying (to avoid reflection overhead on every span).
     */
    private volatile boolean triedContextClassLoader = false;

    static {
        // Initialize with the vendored/relocated embedded ProfilerSdk as the default.
        // After shadow jar relocation, ProfilerSdkFactory becomes
        // io.otel.pyroscope.shadow.javaagent.ProfilerSdkFactory, creating the relocated ProfilerSdk.
        // This may be replaced later by:
        // - tryLoadFromSystemClassLoader() in AutoConfig, or
        // - lazy TCCL loading below (for manual-start with Spring Boot classloader)
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
        if (!triedContextClassLoader) {
            tryLoadFromContextClassLoader();
        }
        return ProfilerApiHolder.INSTANCE.get();
    }

    /**
     * Attempt to load ProfilerSdk from the thread context classloader.
     * This handles the manual-start case where pyroscope-java is inside a Spring Boot
     * fat jar (loaded by LaunchedURLClassLoader), not the system classloader.
     * The TCCL is set to the app classloader during request processing.
     */
    private void tryLoadFromContextClassLoader() {
        triedContextClassLoader = true;
        try {
            // If the current holder's profiler is already started (e.g. in the library case
            // where everything is in one classloader), skip the TCCL check.
            ProfilerApi current = ProfilerApiHolder.INSTANCE.get();
            if (current != null && current.isProfilingStarted()) {
                return;
            }
            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl == null) {
                return;
            }
            // Constructed at runtime so the shadow jar relocator doesn't rename it
            String className = String.join(".", "io", "pyroscope", "javaagent", "ProfilerSdk");
            Class<?> sdkClass = tccl.loadClass(className);
            Constructor<?> ctor = sdkClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object sdkInstance = ctor.newInstance();
            // Check if the profiler has been started (it must be for manual-start mode)
            java.lang.reflect.Method isStarted = sdkClass.getDeclaredMethod("isProfilingStarted");
            boolean started = (boolean) isStarted.invoke(sdkInstance);
            if (!started) {
                // Profiler not started yet — keep using the embedded fallback.
                // Reset so we try again on next span.
                triedContextClassLoader = false;
                return;
            }
            // Use reflection to call setTracingContext/registerConstant since we can't
            // cast across classloaders (ProfilerApi is a different class in each CL).
            ProfilerApi bridge = new ReflectiveProfilerApiBridge(sdkInstance);
            ProfilerApiHolder.INSTANCE.set(bridge);
            System.out.println("[pyroscope-otel] SpanProcessor: Loaded ProfilerSdk from TCCL: " + tccl.getClass().getName());
        } catch (ClassNotFoundException e) {
            // ProfilerSdk not available on TCCL — keep using embedded fallback
        } catch (Exception e) {
            System.out.println("[pyroscope-otel] SpanProcessor: Failed to load from TCCL: " + e.getMessage());
        }
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
