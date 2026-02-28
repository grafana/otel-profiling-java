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
 * (from the app classloader) can be directly cast to ProfilerApi without reflection.
 */
public class PyroscopeSdkInstrumentationModule extends InstrumentationModule {

    public PyroscopeSdkInstrumentationModule() {
        super("pyroscope-sdk", "pyroscope-sdk-1.0");
    }

    @Override
    public List<String> getAdditionalHelperClassNames() {
        return Arrays.asList(
            "io.pyroscope.agent.api.ProfilerApi",
            "io.pyroscope.agent.api.ProfilerApi$Holder",
            // The advice class and its enclosing class must be injected because ByteBuddy
            // inlines the advice code but the JVM still needs to resolve static field
            // references on the advice class.
            "io.otel.pyroscope.instrumentation.ProfilerSdkInstrumentation",
            "io.otel.pyroscope.instrumentation.ProfilerSdkInstrumentation$StartAdvice"
        );
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new ProfilerSdkInstrumentation());
    }
}
