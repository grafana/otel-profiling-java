package io.otel.pyroscope;


import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.javaagent.ProfilerSdkFactory;
import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerApiHolder;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;

import static io.otel.pyroscope.OtelCompat.getBoolean;


public class PyroscopeOtelAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {


    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        // Inject the shared API classes (ProfilerApi, ProfilerApiHolder, ProfilerScopedContext)
        // into the bootstrap classloader BEFORE any code references them. This ensures all
        // classloaders (extension CL, app CL) resolve the same class with the same static fields.
        BootstrapApiInjector.ensureInjected();

        autoConfiguration.addTracerProviderCustomizer((tpBuilder, cfg) -> {
            // Seed the holder with the embedded (relocated) ProfilerSdk as a safe fallback.
            // This must happen before tryLoadFromSystemClassLoader() and startProfiling()
            // because PyroscopeOtelSpanProcessor (whose static initializer also seeds) may
            // not be loaded yet at this point.
            ProfilerApiHolder.INSTANCE.compareAndSet(null, ProfilerSdkFactory.create());

            // Try to load ProfilerSdk from system classloader.
            // This handles the case where pyroscope-java is on the system classpath
            // (e.g., loaded as a -javaagent). The cast works because ProfilerApi
            // is injected into the bootstrap classloader by the instrumentation module,
            // and ProfilerSdk (from system classloader) implements the same interface.
            tryLoadFromSystemClassLoader();

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

    private static void tryLoadFromSystemClassLoader() {
        try {
            ClassLoader systemCL = ClassLoader.getSystemClassLoader();
            PyroscopeOtelDebug.log("AutoConfig: Trying to load ProfilerSdk from system classloader: " + systemCL);
            PyroscopeOtelDebug.log("AutoConfig: System classloader class: " + systemCL.getClass().getName());
            // Constructed at runtime so the shadow jar relocator doesn't rename it
            String className = String.join(".", "io", "pyroscope", "javaagent", "ProfilerSdk");
            PyroscopeOtelDebug.log("AutoConfig: Loading class: " + className);
            Class<?> sdkClass = systemCL.loadClass(className);
            PyroscopeOtelDebug.log("AutoConfig: Loaded ProfilerSdk, classloader: " + sdkClass.getClassLoader());
            Constructor<?> ctor = sdkClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            ProfilerApi bridge = (ProfilerApi) ctor.newInstance();
            PyroscopeOtelDebug.log("AutoConfig: Cast to ProfilerApi succeeded! Using system-classloader ProfilerSdk.");
            ProfilerApiHolder.INSTANCE.set(bridge);
        } catch (Exception e) {
            PyroscopeOtelDebug.log("AutoConfig: Could not load ProfilerSdk from system classloader, will continue with the built-in one: " + e.getMessage());
            if (PyroscopeOtelDebug.DEBUG) {
                ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
                if (systemClassLoader instanceof URLClassLoader) {
                    PyroscopeOtelDebug.log("  JARs visible to system classloader:");
                    for (URL url : ((URLClassLoader) systemClassLoader).getURLs()) {
                        PyroscopeOtelDebug.log("    " + url);
                    }
                } else {
                    PyroscopeOtelDebug.log("  System classloader is not a URLClassLoader (" + systemClassLoader.getClass().getName() + "), cannot list JARs");
                }
            }
        }
    }
}
