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

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;


public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

    private final PyroscopeOtelConfiguration configuration;
    private volatile OtelProfilerSdkBridge profilerSdk;
    private volatile AsyncProfiler asprof;
    private final Callable<OtelProfilerSdkBridge> lazyResolver;
    private final AtomicBoolean resolved = new AtomicBoolean(false);

    public PyroscopeOtelSpanProcessor(PyroscopeOtelConfiguration configuration, OtelProfilerSdkBridge profilerSdk) {
        this.configuration = configuration;
        this.profilerSdk = profilerSdk;
        this.lazyResolver = null;
        this.resolved.set(true);
        if (profilerSdk == null) {
            asprof = PyroscopeAsyncProfiler.getAsyncProfiler();
        } else {
            asprof = null;
        }
    }

    /**
     * Constructor for deferred bridge initialization. Used when the Pyroscope SDK is not yet
     * visible at OTel premain time (e.g. Spring Boot fat JAR with
     * {@code OTEL_PYROSCOPE_START_PROFILING=false}). The {@code lazyResolver} is invoked once
     * on the first span, by which time Spring Boot's {@code LaunchedURLClassLoader} has been
     * set up and the SDK classes are reachable.
     */
    PyroscopeOtelSpanProcessor(PyroscopeOtelConfiguration configuration, Callable<OtelProfilerSdkBridge> lazyResolver) {
        this.configuration = configuration;
        this.profilerSdk = null;
        this.asprof = null;
        this.lazyResolver = lazyResolver;
        // resolved stays false — tryResolveOnce() will fire on the first span
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
        if (lazyResolver != null && !resolved.get()) {
            tryResolveOnce();
        }
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

    private void tryResolveOnce() {
        if (!resolved.compareAndSet(false, true)) {
            return; // another thread already won the race
        }
        System.out.println("[pyroscope-otel] Attempting deferred bridge initialization on first span...");
        try {
            OtelProfilerSdkBridge bridge = lazyResolver.call();
            this.profilerSdk = bridge;
            System.out.println("[pyroscope-otel] Deferred bridge initialization succeeded: " + bridge);
        } catch (Exception e) {
            this.asprof = PyroscopeAsyncProfiler.getAsyncProfiler();
            System.out.println("[pyroscope-otel] Deferred bridge initialization failed: " + e.getMessage() + ", using bundled profiler.");
        }
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
