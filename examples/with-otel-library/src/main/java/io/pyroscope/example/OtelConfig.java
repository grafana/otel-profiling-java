package io.pyroscope.example;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.otel.pyroscope.PyroscopeOtelConfiguration;
import io.otel.pyroscope.PyroscopeOtelSpanProcessor;
import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
public class OtelConfig {

    @Value("${pyroscope.server.address}")
    private String pyroscopeServerAddress;

    @Value("${pyroscope.application.name}")
    private String applicationName;

    @PostConstruct
    public void init() {
        // Start Pyroscope profiling agent programmatically.
        // The agent sends profiling data to the configured Pyroscope server.
        // TODO: io.pyroscope:agent is not yet published - update if the Config API changes in v2.0.0.
        PyroscopeAgent.start(
                new Config.Builder()
                        .setApplicationName(applicationName)
                        .setProfilingEvent(EventType.ITIMER)
                        .setFormat(Format.JFR)
                        .setServerAddress(pyroscopeServerAddress)
                        .build()
        );

        // Create the Pyroscope span processor that links profiling data to OTel trace spans.
        // Pass null for the profilerSdk bridge - the profiler and the span processor share the
        // same classloader here (library usage), so the processor uses async-profiler APIs directly.
        PyroscopeOtelSpanProcessor pyroscopeProcessor = new PyroscopeOtelSpanProcessor(
                new PyroscopeOtelConfiguration.Builder()
                        .setRootSpanOnly(true)
                        .setAddSpanName(true)
                        .build(),
                null
        );

        // Build the OpenTelemetry SDK with the Pyroscope span processor and register it globally.
        // WorkController retrieves the global tracer to create spans manually.
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(pyroscopeProcessor)
                .build();

        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }
}
