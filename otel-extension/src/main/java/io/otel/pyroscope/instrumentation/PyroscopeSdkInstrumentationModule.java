package io.otel.pyroscope.instrumentation;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;

import java.util.Collections;
import java.util.List;

/**
 * Instrumentation module that hooks PyroscopeAgent.start() to capture a ProfilerSdk
 * instance from the classloader that loaded PyroscopeAgent (e.g. Spring Boot CL).
 *
 * ProfilerApi, ProfilerApiHolder, and ProfilerScopedContext are injected into the
 * bootstrap classloader by {@link io.otel.pyroscope.BootstrapApiInjector} at extension
 * startup, so they don't need to be listed as helper classes here. Both the extension CL
 * and app CL delegate to bootstrap and resolve the same class with the same static fields.
 */
public class PyroscopeSdkInstrumentationModule extends InstrumentationModule {

    public PyroscopeSdkInstrumentationModule() {
        super("pyroscope-sdk", "pyroscope-sdk-1.0");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new ProfilerSdkInstrumentation());
    }
}
