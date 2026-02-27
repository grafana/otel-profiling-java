package io.pyroscope.example;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.otel.pyroscope.OtelProfilerSdkBridge;
import io.otel.pyroscope.PyroscopeOtelConfiguration;
import io.otel.pyroscope.PyroscopeOtelSpanProcessor;
import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.ProfilerSdk;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.labels.v2.Pyroscope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.lang.reflect.Method;

@Configuration
public class OtelConfig {

    @Value("${pyroscope.server.address}")
    private String pyroscopeServerAddress;

    @Value("${pyroscope.application.name}")
    private String applicationName;

    @PostConstruct
    public void init() throws Exception {
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

        // Use ProfilerSdk (not shaded in pyroscope-otel.jar) to bridge into the running profiler.
        // This is required so that registerConstant() and setTracingContext() route through the
        // unshaded io.pyroscope:agent classes that the running profiler actually uses.
        // Passing null would instead call into the shaded copies bundled inside pyroscope-otel.jar,
        // which are disconnected from the PyroscopeAgent started above.
        ProfilerSdk profilerSdk = new ProfilerSdk();
        Method registerConstant = Pyroscope.LabelsWrapper.class.getDeclaredMethod("registerConstant", String.class);
        OtelProfilerSdkBridge bridge = new OtelProfilerSdkBridge(profilerSdk, registerConstant);

        PyroscopeOtelSpanProcessor pyroscopeProcessor = new PyroscopeOtelSpanProcessor(
                new PyroscopeOtelConfiguration.Builder()
                        .setRootSpanOnly(true)
                        .setAddSpanName(true)
                        .build(),
                bridge
        );

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(pyroscopeProcessor)
                .addSpanProcessor(new LoggingSpanProcessor())
                .build();

        OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
    }
}
