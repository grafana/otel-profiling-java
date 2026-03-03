package io.otel.pyroscope.instrumentation;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Instrumentation module that hooks PyroscopeAgent.start() to capture a ProfilingBridgeImpl
 * instance from the classloader that loaded PyroscopeAgent (e.g. Spring Boot CL).
 *
 * Injects IProfilingBridge into the instrumented classloader so that ProfilingBridgeImpl
 * (from the app classloader) can be directly cast without reflection.
 */
public class PyroscopeSdkInstrumentationModule extends InstrumentationModule {

    public PyroscopeSdkInstrumentationModule() {
        super("pyroscope-sdk", "pyroscope-sdk-1.0");
    }

    @Override
    public List<String> getAdditionalHelperClassNames() {
        // Inject IProfilingBridge and its Holder into the instrumented classloader so that:
        // 1. ProfilingBridgeImpl (which implements IProfilingBridge) can load
        // 2. The advice can cast the ProfilingBridgeImpl instance to IProfilingBridge
        // 3. The advice can set IProfilingBridge.Holder.INSTANCE for the span processor
        return Arrays.asList(
            "io.pyroscope.agent.api.IProfilingBridge",
            "io.pyroscope.agent.api.IProfilingBridge$Holder"
        );
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new ProfilerSdkInstrumentation());
    }
}
