package io.otel.pyroscope.instrumentation;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Instrumentation module that hooks PyroscopeAgent.start() to capture a ProfilerSdk
 * instance from the classloader that loaded PyroscopeAgent (e.g. Spring Boot CL).
 *
 * Injects ProfilerApi into the instrumented classloader so that ProfilerSdk
 * (from the app classloader) can be directly cast without reflection.
 */
public class PyroscopeSdkInstrumentationModule extends InstrumentationModule {

    public PyroscopeSdkInstrumentationModule() {
        super("pyroscope-sdk", "pyroscope-sdk-1.0");
    }

    @Override
    public List<String> getAdditionalHelperClassNames() {
        // Inject ProfilerApi and ProfilerApiHolder into the instrumented classloader so that:
        // 1. ProfilerSdk (which implements ProfilerApi) can load
        // 2. The advice can cast the ProfilerSdk instance to ProfilerApi
        // 3. The advice can set ProfilerApiHolder.INSTANCE for the span processor
        return Arrays.asList(
            "io.pyroscope.javaagent.api.ProfilerApi",
            "io.pyroscope.javaagent.api.ProfilerApiHolder",
            "io.pyroscope.javaagent.api.ProfilerScopedContext"
        );
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new ProfilerSdkInstrumentation());
    }
}
