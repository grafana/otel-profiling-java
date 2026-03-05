package io.pyroscope.example;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
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
        // TODO: io.pyroscope:agent is not yet published - update if the Config API changes in v2.0.0.
        PyroscopeAgent.start(
                new Config.Builder()
                        .setApplicationName(applicationName)
                        .setProfilingEvent(EventType.ITIMER)
                        .setFormat(Format.JFR)
                        .setServerAddress(pyroscopeServerAddress)
                        .build()
        );

        PyroscopeOtelSpanProcessor pyroscopeProcessor = new PyroscopeOtelSpanProcessor();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(pyroscopeProcessor)
                .addSpanProcessor(new LoggingSpanProcessor())
                .build();

        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }
}
