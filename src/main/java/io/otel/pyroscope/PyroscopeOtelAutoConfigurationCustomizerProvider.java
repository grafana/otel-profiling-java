package io.otel.pyroscope;


import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.agent.api.ProfilerApi;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Base64;

import static io.otel.pyroscope.OtelCompat.getBoolean;


public class PyroscopeOtelAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {


    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer((tpBuilder, cfg) -> {
            // Try to load ProfilerSdk from system classloader.
            // This handles the case where pyroscope-java is on the system classpath
            // (e.g., loaded as a -javaagent). The cast works because ProfilerApi
            // is injected into the bootstrap classloader by the instrumentation module,
            // and ProfilerSdk (from system classloader) implements the same ProfilerApi.
            tryLoadProfilerSdkFromSystemClassLoader();

            boolean startProfiling = getBoolean(cfg, "otel.pyroscope.start.profiling", true);
            if (startProfiling) {
                PyroscopeOtelSpanProcessor.PROFILER.get().startProfiling();
            }

            PyroscopeOtelConfiguration pyroOtelConfig = new PyroscopeOtelConfiguration.Builder()
                    .setRootSpanOnly(getBoolean(cfg, "otel.pyroscope.root.span.only", true))
                    .setAddSpanName(getBoolean(cfg, "otel.pyroscope.add.span.name", true))
                    .build();

            return tpBuilder.addSpanProcessor(
                    new PyroscopeOtelSpanProcessor(pyroOtelConfig));
        });
    }

    private static void tryLoadProfilerSdkFromSystemClassLoader() {
        try {
            ClassLoader systemCL = ClassLoader.getSystemClassLoader();
            System.out.println("[pyroscope-otel] AutoConfig: Trying to load ProfilerSdk from system classloader: " + systemCL);
            System.out.println("[pyroscope-otel] AutoConfig: System classloader class: " + systemCL.getClass().getName());
            // Use Base64 encoding to prevent shadow jar relocation from renaming this string
            String className = getProfilerSdkClassName();
            System.out.println("[pyroscope-otel] AutoConfig: Loading class: " + className);
            Class<?> sdkClass = systemCL.loadClass(className);
            System.out.println("[pyroscope-otel] AutoConfig: Loaded ProfilerSdk, classloader: " + sdkClass.getClassLoader());
            Constructor<?> ctor = sdkClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            ProfilerApi sdk = (ProfilerApi) ctor.newInstance();
            System.out.println("[pyroscope-otel] AutoConfig: Cast to ProfilerApi succeeded! Using system-classloader ProfilerSdk.");
            PyroscopeOtelSpanProcessor.PROFILER.set(sdk);
        } catch (Exception e) {
            // ProfilerSdk not on system classpath - using default vendored SDK
            System.out.println("[pyroscope-otel] AutoConfig: Could not load ProfilerSdk from system classloader, will continue with the built-in one!");
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
    }

    private static String getProfilerSdkClassName() {
        // otherwise the relocate plugin renames this string :shrug:
        // decodes to: io.pyroscope.javaagent.ProfilerSdk
        return new String(Base64.getDecoder().decode("aW8ucHlyb3Njb3BlLmphdmFhZ2VudC5Qcm9maWxlclNkaw=="));
    }
}