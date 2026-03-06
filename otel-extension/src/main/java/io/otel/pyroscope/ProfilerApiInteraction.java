package io.otel.pyroscope;

import io.pyroscope.javaagent.ProfilerSdkFactory;
import io.pyroscope.javaagent.api.ProfilerApi;
import io.pyroscope.javaagent.api.ProfilerApiHolder;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;

class ProfilerApiInteraction {

    static void ensureProfilerApiSet() {
        if (ProfilerApiHolder.INSTANCE.get() != null) {
            return;
        }

        ProfilerApi api = tryLoadFromSystemClassLoader();
        if (api == null) {
            api = ProfilerSdkFactory.create();
            PyroscopeOtelDebug.log("ProfilerApiInteraction: Using embedded (relocated) ProfilerSdk as fallback");
        }

        ProfilerApiHolder.INSTANCE.compareAndSet(null, api);
    }

    private static ProfilerApi tryLoadFromSystemClassLoader() {
        try {
            ClassLoader systemCL = ClassLoader.getSystemClassLoader();
            PyroscopeOtelDebug.log("ProfilerApiInteraction: Trying to load ProfilerSdk from system classloader: " + systemCL);
            // Constructed at runtime so the shadow jar relocator doesn't rename it
            String className = String.join(".", "io", "pyroscope", "javaagent", "ProfilerSdk");
            Class<?> sdkClass = systemCL.loadClass(className);
            PyroscopeOtelDebug.log("ProfilerApiInteraction: Loaded ProfilerSdk, classloader: " + sdkClass.getClassLoader());
            Constructor<?> ctor = sdkClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            ProfilerApi api = (ProfilerApi) ctor.newInstance();
            PyroscopeOtelDebug.log("ProfilerApiInteraction: Using system-classloader ProfilerSdk");
            return api;
        } catch (Exception e) {
            PyroscopeOtelDebug.log("ProfilerApiInteraction: Could not load from system classloader: " + e.getMessage());
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
            return null;
        }
    }
}
