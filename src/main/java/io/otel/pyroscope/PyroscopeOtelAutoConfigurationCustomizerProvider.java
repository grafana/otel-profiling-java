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
            boolean loadFailed = false;
            try {
                profilerSdk = loadProfilerSdk();
            } catch (Exception e) {
                System.out.println("[pyroscope-otel] Could not load the profiler SDK at startup.");
                System.out.println("[pyroscope-otel]   Reason: " + e.getMessage());
                ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
                if (systemClassLoader instanceof URLClassLoader) {
                    System.out.println("[pyroscope-otel]   JARs visible to system classloader:");
                    for (URL url : ((URLClassLoader) systemClassLoader).getURLs()) {
                        System.out.println("[pyroscope-otel]     " + url);
                    }
                } else {
                    System.out.println("[pyroscope-otel]   System classloader is not a URLClassLoader (" + systemClassLoader.getClass().getName() + "), cannot list JARs");
                }
                loadFailed = true;
            }

            boolean startProfiling = getBoolean(cfg, "otel.pyroscope.start.profiling", true);

            PyroscopeOtelConfiguration pyroOtelConfig = new PyroscopeOtelConfiguration.Builder()
                    .setRootSpanOnly(getBoolean(cfg, "otel.pyroscope.root.span.only", true))
                    .setAddSpanName(getBoolean(cfg, "otel.pyroscope.add.span.name", true))
                    .build();

            PyroscopeOtelSpanProcessor processor;
            if (!loadFailed) {
                // Bridge found immediately — covers the -javaagent premain case where pyroscope
                // is in the system classloader (not inside a Spring Boot fat JAR).
                if (startProfiling && !profilerSdk.isProfilingStarted()) {
                    profilerSdk.startProfiling();
                }
                processor = new PyroscopeOtelSpanProcessor(pyroOtelConfig, profilerSdk);
            } else if (!startProfiling) {
                // SDK not found yet AND start.profiling=false: this is the Spring Boot fat JAR +
                // programmatic-start scenario. Spring Boot's LaunchedURLClassLoader (which can see
                // the SDK inside the fat JAR) does not exist yet at premain time — it is created by
                // JarLauncher.main() after the OTel agent finishes. Defer bridge initialization to
                // the first span, by which time Spring Boot will have set up the context classloader.
                System.out.println("[pyroscope-otel] Will retry bridge initialization on first span (Spring Boot fat JAR with programmatic profiler start detected).");
                processor = new PyroscopeOtelSpanProcessor(pyroOtelConfig, PyroscopeOtelAutoConfigurationCustomizerProvider::loadProfilerSdk);
            } else {
                // SDK not found at startup and start.profiling=true: start the bundled profiler now.
                System.out.println("[pyroscope-otel] Starting built-in profiler.");
                PyroscopeAgent.start(Config.build());
                processor = new PyroscopeOtelSpanProcessor(pyroOtelConfig, (OtelProfilerSdkBridge) null);
            }

            return tpBuilder.addSpanProcessor(processor);
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
    static OtelProfilerSdkBridge loadProfilerSdk() {
        LinkedHashSet<ClassLoader> candidates = new LinkedHashSet<>();
        addClassLoaderChain(candidates, Thread.currentThread().getContextClassLoader());
        addClassLoaderChain(candidates, ClassLoader.getSystemClassLoader());

        String sdkClassName = "io.pyroscope.javaagent.ProfilerSdk";
        String labelsClassName = getLabelsWrapperClassName();

        System.out.println("[pyroscope-otel] Searching for Pyroscope SDK across " + candidates.size() + " classloader(s):");
        for (ClassLoader cl : candidates) {
            System.out.println("[pyroscope-otel]   - " + cl);
        }

        Exception lastException = null;
        for (ClassLoader cl : candidates) {
            try {
                Class<?> sdkClass = cl.loadClass(sdkClassName);
                Object sdk = sdkClass.getDeclaredConstructor().newInstance();
                Class<?> labelsWrapperClass = cl.loadClass(labelsClassName);
                Method registerConstant = labelsWrapperClass.getDeclaredMethod("registerConstant", String.class);
                System.out.println("[pyroscope-otel] Pyroscope SDK loaded successfully via: " + cl);
                return new OtelProfilerSdkBridge(sdk, registerConstant);
            } catch (ClassNotFoundException e) {
                System.out.println("[pyroscope-otel]   " + cl + ": class not found (" + e.getMessage() + ")");
                lastException = e;
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