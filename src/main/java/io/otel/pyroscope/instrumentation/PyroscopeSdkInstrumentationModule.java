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
        // Inject ProfilerApi and its Holder into the instrumented classloader so that:
        // 1. ProfilerSdk (which implements ProfilerApi) can be loaded without NoClassDefFoundError
        // 2. The advice can cast the ProfilerSdk instance to ProfilerApi
        // 3. The advice can set ProfilerApi.Holder.INSTANCE for the span processor to pick up
        return Arrays.asList(
            "io.pyroscope.agent.api.ProfilerApi",
            "io.pyroscope.agent.api.ProfilerApi$Holder"
        );
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new ProfilerSdkInstrumentation());
    }
}
