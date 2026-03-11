package io.otel.pyroscope;


import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.javaagent.ProfilerSdkFactory;
import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerApiHolder;

import java.lang.reflect.Constructor;

import static io.otel.pyroscope.OtelCompat.getBoolean;


public class PyroscopeOtelAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {


    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        BootstrapApiInjector.ensureInjected();

        autoConfiguration.addTracerProviderCustomizer((tpBuilder, cfg) -> {
            if (ProfilerApiHolder.INSTANCE.get() == null) {
                ProfilerApi api = tryLoadFromSystemClassLoader();
                if (api == null) {
                    api = ProfilerSdkFactory.create();
                }
                ProfilerApiHolder.INSTANCE.set(api);
            }

            boolean startProfiling = getBoolean(cfg, "otel.pyroscope.start.profiling", true);
            if (startProfiling) {
                ProfilerApiHolder.INSTANCE.get().startProfiling();
            }

            PyroscopeOtelConfiguration pyroOtelConfig = new PyroscopeOtelConfiguration.Builder()
                    .setRootSpanOnly(getBoolean(cfg, "otel.pyroscope.root.span.only", true))
                    .setAddSpanName(getBoolean(cfg, "otel.pyroscope.add.span.name", true))
                    .build();

            return tpBuilder.addSpanProcessor(
                    new PyroscopeOtelSpanProcessor(pyroOtelConfig));
        });
    }

    private static ProfilerApi tryLoadFromSystemClassLoader() {
        try {
            ClassLoader systemCL = ClassLoader.getSystemClassLoader();
            PyroscopeOtelDebug.log("AutoConfig: Trying to load ProfilerSdk from system classloader: " + systemCL);
            String className = String.join(".", "io", "pyroscope", "javaagent", "ProfilerSdk");
            Class<?> sdkClass = systemCL.loadClass(className);
            PyroscopeOtelDebug.log("AutoConfig: Loaded ProfilerSdk, classloader: " + sdkClass.getClassLoader());
            Constructor<?> ctor = sdkClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            ProfilerApi api = (ProfilerApi) ctor.newInstance();
            PyroscopeOtelDebug.log("AutoConfig: Using system-classloader ProfilerSdk");
            return api;
        } catch (Exception e) {
            PyroscopeOtelDebug.log("AutoConfig: Could not load from system classloader: " + e.getMessage());
            return null;
        }
    }
}
