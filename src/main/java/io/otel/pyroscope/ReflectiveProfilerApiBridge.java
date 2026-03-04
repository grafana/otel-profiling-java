package io.otel.pyroscope;

import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerScopedContext;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Reflection-based bridge to a ProfilerSdk instance from a different classloader
 * (e.g. Spring Boot's LaunchedURLClassLoader). Used when the app-classloader's
 * ProfilerSdk can't be directly cast to ProfilerApi because ProfilerApi is loaded
 * by a different classloader (the OTel extension CL).
 */
class ReflectiveProfilerApiBridge implements ProfilerApi {

    private final Object sdkInstance;
    private final Method setTracingContextMethod;
    private final Method registerConstantMethod;
    private final Method startProfilingMethod;
    private final Method isProfilingStartedMethod;

    ReflectiveProfilerApiBridge(Object sdkInstance) throws Exception {
        this.sdkInstance = sdkInstance;
        Class<?> cls = sdkInstance.getClass();
        this.setTracingContextMethod = cls.getDeclaredMethod("setTracingContext", long.class, long.class);
        this.registerConstantMethod = cls.getDeclaredMethod("registerConstant", String.class);
        this.startProfilingMethod = cls.getDeclaredMethod("startProfiling");
        this.isProfilingStartedMethod = cls.getDeclaredMethod("isProfilingStarted");
    }

    @Override
    public void setTracingContext(long spanId, long spanName) {
        try {
            setTracingContextMethod.invoke(sdkInstance, spanId, spanName);
        } catch (Exception e) {
            // Silently ignore — don't break span processing
        }
    }

    @Override
    public long registerConstant(String constant) {
        try {
            return (Long) registerConstantMethod.invoke(sdkInstance, constant);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void startProfiling() {
        try {
            startProfilingMethod.invoke(sdkInstance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isProfilingStarted() {
        try {
            return (boolean) isProfilingStartedMethod.invoke(sdkInstance);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @Deprecated
    public ProfilerScopedContext createScopedContext(Map<String, String> labels) {
        throw new UnsupportedOperationException("createScopedContext not supported across classloaders");
    }
}
