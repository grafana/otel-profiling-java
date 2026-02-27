package io.pyroscope.example;

import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
public class PyroscopeConfig {

    @Value("${pyroscope.server.address}")
    private String serverAddress;

    @Value("${pyroscope.application.name}")
    private String applicationName;

    @PostConstruct
    public void init() {
        // Start Pyroscope profiling programmatically.
        //
        // The pyroscope-otel extension (loaded via -Dotel.javaagent.extensions=pyroscope-otel.jar)
        // is configured with OTEL_PYROSCOPE_START_PROFILING=false so it does NOT auto-start
        // the profiler. Instead, it detects this running PyroscopeAgent via the system
        // ClassLoader bridge (OtelProfilerSdkBridge) and wires PyroscopeOtelSpanProcessor
        // for trace-profile correlation automatically.
        PyroscopeAgent.start(
                new Config.Builder()
                        .setApplicationName(applicationName)
                        .setProfilingEvent(EventType.ITIMER)
                        .setFormat(Format.JFR)
                        .setServerAddress(serverAddress)
                        .build()
        );
    }
}
