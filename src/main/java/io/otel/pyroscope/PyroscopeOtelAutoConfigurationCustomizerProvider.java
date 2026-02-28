package io.otel.pyroscope;


import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Base64;
import java.util.LinkedHashSet;

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
                System.out.println("  Reason: " + e.getMessage());
                ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
                if (systemClassLoader instanceof URLClassLoader) {
                    System.out.println("  JARs visible to system classloader:");
                    for (URL url : ((URLClassLoader) systemClassLoader).getURLs()) {
                        System.out.println("    " + url);
                    }
                } else {
                    System.out.println("  System classloader is not a URLClassLoader (" + systemClassLoader.getClass().getName() + "), cannot list JARs");
                }
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
     * We try multiple classloaders in order:
     * <ol>
     *   <li>Thread context classloader — Spring Boot sets this to {@code LaunchedURLClassLoader} which
     *       sees classes inside the fat JAR (including the user's pyroscope dependency).</li>
     *   <li>System classloader — covers the {@code -javaagent} premain case where pyroscope is loaded
     *       directly into the system classloader.</li>
     *   <li>Parent chains of the above — covers other delegating classloader arrangements.</li>
     * </ol>
     * Both {@code ProfilerSdk} and {@code Pyroscope$LabelsWrapper} are loaded from the same classloader
     * so they operate on the same profiler state and constants map.
     */
    private static OtelProfilerSdkBridge loadProfilerSdk() {
        LinkedHashSet<ClassLoader> candidates = new LinkedHashSet<>();
        addClassLoaderChain(candidates, Thread.currentThread().getContextClassLoader());
        addClassLoaderChain(candidates, ClassLoader.getSystemClassLoader());

        String sdkClassName = "io.pyroscope.javaagent.ProfilerSdk";
        String labelsClassName = getLabelsWrapperClassName();

        Exception lastException = null;
        for (ClassLoader cl : candidates) {
            try {
                Class<?> sdkClass = cl.loadClass(sdkClassName);
                Object sdk = sdkClass.getDeclaredConstructor().newInstance();
                Class<?> labelsWrapperClass = cl.loadClass(labelsClassName);
                Method registerConstant = labelsWrapperClass.getDeclaredMethod("registerConstant", String.class);
                return new OtelProfilerSdkBridge(sdk, registerConstant);
            } catch (ClassNotFoundException e) {
                lastException = e;
                // This classloader doesn't have ProfilerSdk — try the next one.
            } catch (Exception e) {
                // ProfilerSdk was found but failed to instantiate — real error.
                throw new RuntimeException("Error loading the profiler SDK from classloader: " + cl, e);
            }
        }
        throw new RuntimeException("Error loading the profiler SDK", lastException);
    }

    private static void addClassLoaderChain(LinkedHashSet<ClassLoader> candidates, ClassLoader cl) {
        while (cl != null) {
            candidates.add(cl);
            cl = cl.getParent();
        }
    }

    private static String getLabelsWrapperClassName() {
        // otherwise the relocate plugin renames this string :shrug:
        return new String(Base64.getDecoder().decode("aW8ucHlyb3Njb3BlLmxhYmVscy52Mi5QeXJvc2NvcGUkTGFiZWxzV3JhcHBlcg=="));
    }
}