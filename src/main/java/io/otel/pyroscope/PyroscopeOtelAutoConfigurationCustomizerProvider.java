package io.otel.pyroscope;


import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;

import static io.otel.pyroscope.OtelCompat.getBoolean;


public class PyroscopeOtelAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {


    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer((tpBuilder, cfg) -> {
            OtelProfilerSdkBridge profilerSdk = null;
            try {
                profilerSdk = loadProfilerSdk();
            } catch (Exception e) {
                // This usually means we are running without the Pyroscope SDK.
                // We'll instead use the Profiler bundled with the extension.
                System.out.println("Could not load the profiler SDK, will continue with the built-in one!");
            }

            boolean startProfiling = getBoolean(cfg, "otel.pyroscope.start.profiling", true);
            if (startProfiling) {
                if (profilerSdk == null) {
                    PyroscopeAgent.start(Config.build());
                } else if (!profilerSdk.isProfilingStarted()) {
                    profilerSdk.startProfiling();
                }
            }

            PyroscopeOtelConfiguration pyroOtelConfig = new PyroscopeOtelConfiguration.Builder()
                    .setRootSpanOnly(getBoolean(cfg, "otel.pyroscope.root.span.only", true))
                    .setAddSpanName(getBoolean(cfg, "otel.pyroscope.add.span.name", true))
                    .build();

            return tpBuilder.addSpanProcessor(
                    new PyroscopeOtelSpanProcessor(
                            pyroOtelConfig,
                            profilerSdk
                    ));
        });
    }

    /**
     * Open Telemetry extension classes are loaded by an isolated class loader.
     * As such, they can't communicate with other parts of the application (e.g., the Pyroscope SDK).
     * <p>
     * If the Pyroscope SDK is loaded as a java agent, we'll access it via the system class loader and interact with it
     * via a bridge.
     */
    private static OtelProfilerSdkBridge loadProfilerSdk() {
        try {
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            Class<?> sdkClass = systemClassLoader.loadClass("io.pyroscope.javaagent.ProfilerSdk");
            Object sdk = sdkClass.getDeclaredConstructor().newInstance();
            return new OtelProfilerSdkBridge(sdk);
        } catch (Exception e) {
            throw new RuntimeException("Error loading the profiler SDK", e);
        }
    }

}