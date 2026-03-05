package io.otel.pyroscope;

import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerScopedContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiConsumer;

public class OtelProfilerSdkBridge implements ProfilerApi {

    // Flag to enable/disable instrumentation capture
    public static boolean appSdkEnabled = false;

    private static Object sdkInstance;
    private static Method registerConstant;
    private static Method setTracingContextMethod;
    private static Method startProfilingMethod;
    private static Method isProfilingStartedMethod;
    private static Method createScopedContextMethod;

    public OtelProfilerSdkBridge(Object sdkInstance, Method registerConstant) {
        OtelProfilerSdkBridge.sdkInstance = sdkInstance;
        OtelProfilerSdkBridge.registerConstant = registerConstant;
        cacheMethods(sdkInstance);
    }

    /**
     * Called by instrumentation when ProfilerSdk is constructed in App ClassLoader.
     * Only sets the instance if appSdkEnabled is true.
     */
    public static void setSdkInstance(Object sdk) {
        if (!appSdkEnabled) {
            return;
        }
        sdkInstance = sdk;
        cacheMethods(sdk);
    }

    private static void cacheMethods(Object sdk) {
        try {
            Class<?> sdkClass = sdk.getClass();
            setTracingContextMethod = sdkClass.getDeclaredMethod("setTracingContext", long.class, long.class);
            startProfilingMethod = sdkClass.getDeclaredMethod("startProfiling");
            isProfilingStartedMethod = sdkClass.getDeclaredMethod("isProfilingStarted");
            createScopedContextMethod = sdkClass.getDeclaredMethod("createScopedContext", Map.class);

            Class<?> labelsClass = sdk.getClass().getClassLoader()
                .loadClass("io.pyroscope.labels.v2.Pyroscope$LabelsWrapper");
            registerConstant = labelsClass.getMethod("registerConstant", String.class);
        } catch (Exception e) {
            // Best effort
        }
    }

    public static boolean isAvailable() {
        return sdkInstance != null;
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
            throw new RuntimeException(e);
        }
    }

    @Override
    public ProfilerScopedContext createScopedContext(Map<String, String> labels) {
        try {
            Object ctx = createScopedContextMethod.invoke(sdkInstance, labels);

            return new ProfilerScopedContext() {

                @Override
                public void forEachLabel(BiConsumer<String, String> biConsumer) {
                    try {
                        Method forEachLabelMethod = ctx.getClass().getDeclaredMethod("forEachLabel", BiConsumer.class);
                        forEachLabelMethod.invoke(ctx, biConsumer);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public void close() {
                    try {
                        Method closeMethod = ctx.getClass().getDeclaredMethod("close");
                        closeMethod.invoke(ctx);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setTracingContext(long profileId, long spanName) {
        try {
            setTracingContextMethod.invoke(sdkInstance, profileId, spanName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long registerConstant(String s) {
        try {
            return (Long) registerConstant.invoke(null, s);
        } catch (Exception e) {
            return 0;
        }
    }
}
