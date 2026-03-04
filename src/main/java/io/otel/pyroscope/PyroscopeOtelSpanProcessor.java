package io.otel.pyroscope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerApiHolder;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;


public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

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
            debugInspectTcclHolder();
        }
        return ProfilerApiHolder.INSTANCE.get();
    }

    /**
     * Debug: inspect ProfilerApiHolder from the TCCL and compare with our own.
     */
    private void debugInspectTcclHolder() {
        triedContextClassLoader = true;
        try {
            ProfilerApi current = ProfilerApiHolder.INSTANCE.get();
            System.out.println("[pyroscope-otel] DEBUG SpanProcessor: === TCCL Holder Inspection ===");
            System.out.println("[pyroscope-otel] DEBUG SpanProcessor: Extension CL ProfilerApiHolder class: " + ProfilerApiHolder.class + " loaded by: " + ProfilerApiHolder.class.getClassLoader());
            System.out.println("[pyroscope-otel] DEBUG SpanProcessor: Extension CL ProfilerApiHolder.INSTANCE identity: " + System.identityHashCode(ProfilerApiHolder.INSTANCE));
            System.out.println("[pyroscope-otel] DEBUG SpanProcessor: Extension CL ProfilerApiHolder.INSTANCE.get(): " + current);
            if (current != null) {
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: Extension CL current profiler class: " + current.getClass() + " loaded by: " + current.getClass().getClassLoader());
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: Extension CL current.isProfilingStarted(): " + current.isProfilingStarted());
            }

            ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            System.out.println("[pyroscope-otel] DEBUG SpanProcessor: TCCL: " + tccl);
            if (tccl == null) {
                return;
            }
            System.out.println("[pyroscope-otel] DEBUG SpanProcessor: TCCL class: " + tccl.getClass().getName());

            // Try to load ProfilerApiHolder from TCCL
            String holderClassName = String.join(".", "io", "pyroscope", "javaagent", "api", "ProfilerApiHolder");
            try {
                Class<?> tcclHolderClass = tccl.loadClass(holderClassName);
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: TCCL ProfilerApiHolder class: " + tcclHolderClass + " loaded by: " + tcclHolderClass.getClassLoader());
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: Same class? " + (tcclHolderClass == ProfilerApiHolder.class));

                Field instanceField = tcclHolderClass.getDeclaredField("INSTANCE");
                @SuppressWarnings("unchecked")
                AtomicReference<Object> tcclInstance = (AtomicReference<Object>) instanceField.get(null);
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: TCCL ProfilerApiHolder.INSTANCE identity: " + System.identityHashCode(tcclInstance));
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: Same INSTANCE? " + (System.identityHashCode(tcclInstance) == System.identityHashCode(ProfilerApiHolder.INSTANCE)));

                Object tcclValue = tcclInstance.get();
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: TCCL ProfilerApiHolder.INSTANCE.get(): " + tcclValue);
                if (tcclValue != null) {
                    System.out.println("[pyroscope-otel] DEBUG SpanProcessor: TCCL value class: " + tcclValue.getClass() + " loaded by: " + tcclValue.getClass().getClassLoader());
                }
            } catch (ClassNotFoundException e) {
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: ProfilerApiHolder NOT found on TCCL");
            }

            // Also try to load ProfilerSdk from TCCL
            String sdkClassName = String.join(".", "io", "pyroscope", "javaagent", "ProfilerSdk");
            try {
                Class<?> tcclSdkClass = tccl.loadClass(sdkClassName);
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: TCCL ProfilerSdk class: " + tcclSdkClass + " loaded by: " + tcclSdkClass.getClassLoader());
            } catch (ClassNotFoundException e) {
                System.out.println("[pyroscope-otel] DEBUG SpanProcessor: ProfilerSdk NOT found on TCCL");
            }

            System.out.println("[pyroscope-otel] DEBUG SpanProcessor: === End Inspection ===");
        } catch (Exception e) {
            System.out.println("[pyroscope-otel] DEBUG SpanProcessor: Inspection failed: " + e);
            e.printStackTrace();
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
