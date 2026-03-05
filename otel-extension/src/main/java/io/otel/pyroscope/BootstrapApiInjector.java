package io.otel.pyroscope;

import io.opentelemetry.javaagent.bootstrap.InstrumentationHolder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * Injects the shared ProfilerApi classes into the bootstrap classloader at extension startup.
 * This ensures that both the ExtensionClassLoader (span processor) and the app classloader
 * (ByteBuddy advice) resolve the same ProfilerApiHolder class with the same static fields.
 */
class BootstrapApiInjector {

    private static volatile boolean injected = false;

    static void ensureInjected() {
        if (injected) {
            return;
        }
        synchronized (BootstrapApiInjector.class) {
            if (injected) {
                return;
            }
            inject();
            injected = true;
        }
    }

    private static void inject() {
        try {
            Instrumentation instrumentation = InstrumentationHolder.getInstrumentation();
            if (instrumentation == null) {
                PyroscopeOtelDebug.log("BootstrapApiInjector: Instrumentation not available, skipping bootstrap injection");
                return;
            }

            // Extract the embedded bootstrap jar from the extension jar's resources
            // Resource uses .bin extension to prevent the shadow jar plugin from merging it
            try (InputStream is = BootstrapApiInjector.class.getResourceAsStream("/pyroscope-otel-bootstrap.jar.bin")) {
                if (is == null) {
                    PyroscopeOtelDebug.log("BootstrapApiInjector: pyroscope-otel-bootstrap.jar.bin not found in resources, skipping");
                    return;
                }
                Path tempJar = Files.createTempFile("pyroscope-otel-bootstrap-", ".jar");
                tempJar.toFile().deleteOnExit();
                Files.copy(is, tempJar, StandardCopyOption.REPLACE_EXISTING);

                // TODO: Instead of extracting a separate jar from resources, consider including
                // the API classes directly in the extension jar and passing the extension jar
                // itself here, using a JarFile subclass that filters entries to only return the
                // three bootstrap-api classes. This would avoid the temp file extraction step.
                instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJar.toFile()));
                PyroscopeOtelDebug.log("BootstrapApiInjector: Injected API classes into bootstrap classloader");
            }
        } catch (IOException e) {
            PyroscopeOtelDebug.log("BootstrapApiInjector: Failed to inject: " + e.getMessage(), e);
        }
    }

}
