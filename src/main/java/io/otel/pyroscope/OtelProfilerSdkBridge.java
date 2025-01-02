package io.otel.pyroscope;

import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerScopedContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiConsumer;

public class OtelProfilerSdkBridge implements ProfilerApi {

    private final Object sdkInstance;

    public OtelProfilerSdkBridge(Object sdkInstance) {
        this.sdkInstance = sdkInstance;
    }

    @Override
    public void startProfiling() {
        try {
            Method method = sdkInstance.getClass().getDeclaredMethod("startProfiling");
            method.invoke(sdkInstance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isProfilingStarted() {
        try {
            Method method = sdkInstance.getClass().getDeclaredMethod("isProfilingStarted");
            return (boolean) method.invoke(sdkInstance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ProfilerScopedContext createScopedContext(Map<String, String> labels) {
        try {
            Method method = sdkInstance.getClass().getDeclaredMethod("createScopedContext", Map.class);
            Object ctx = method.invoke(sdkInstance, labels);

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
}
