package io.otel.pyroscope;


import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;

import static io.otel.pyroscope.OtelCompat.getBoolean;


public class PyroscopeOtelAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {

    public static final String CONFIG_APP_NAME = "otel.pyroscope.app.name";
    public static final String CONFIG_ENDPOINT = "otel.pyroscope.endpoint";

    private static final String PYROSCOPE_APPLICATION_NAME_CONFIG = "pyroscope.application.name";
    private static final String PYROSCOPE_SERVER_ADDRESS_CONFIG = "pyroscope.server.address";
    public static final String CONFIG_BASELINE_LABELS = "otel.pyroscope.baseline.labels";

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer((tpBuilder, cfg) -> {
            boolean startProfiling = getBoolean(cfg, "otel.pyroscope.start.profiling", true);
            if (startProfiling) {
                Config pyroConfig = Config.build();
                PyroscopeAgent.start(pyroConfig);
            }
            PyroscopeOtelConfiguration pyroOtelConfig = new PyroscopeOtelConfiguration.Builder()
                    .setRootSpanOnly(getBoolean(cfg, "otel.pyroscope.root.span.only", true))
                    .setAddSpanName(getBoolean(cfg, "otel.pyroscope.add.span.name", true))
                    .build();
            return tpBuilder.addSpanProcessor(
                    new PyroscopeOtelSpanProcessor(
                            pyroOtelConfig
                    ));
        });

    }

}