package io.otel.pyroscope;


import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;

import java.util.Map;

import static io.otel.pyroscope.OtelCompat.getBoolean;
import static io.otel.pyroscope.OtelCompat.getString;
import static io.pyroscope.javaagent.config.AppName.*;


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

            String appName = cfg.getString(CONFIG_APP_NAME);
            String endpoint = cfg.getString(CONFIG_ENDPOINT);
            if (startProfiling) {
                Config pyroConfig = Config.build();
                PyroscopeAgent.start(pyroConfig);
                if (appName == null) {
                    appName = pyroConfig.applicationName + "." + pyroConfig.profilingEvent.id;
                }
                if (endpoint == null) {
                    endpoint = pyroConfig.serverAddress;
                }
            } else {
                if (appName == null) {
                    appName = cfg.getString(PYROSCOPE_APPLICATION_NAME_CONFIG);
                    if (appName == null) {
                        throw new IllegalArgumentException("appName == null. appName is required. " +
                                "Provide with " + CONFIG_APP_NAME + " or " + PYROSCOPE_APPLICATION_NAME_CONFIG);
                    }
                }
                if (endpoint == null) {
                    endpoint = cfg.getString(PYROSCOPE_SERVER_ADDRESS_CONFIG);
                    if (endpoint == null) {
                        throw new IllegalArgumentException("endpoint == null. endpoint is required.\n" +
                                "Provide with " + CONFIG_APP_NAME + " or " + PYROSCOPE_APPLICATION_NAME_CONFIG);
                    }
                }
            }
            Map<String, String> labels = parseLabels(getString(cfg, CONFIG_BASELINE_LABELS, ""));
            PyroscopeOtelConfiguration pyroOtelConfig = new PyroscopeOtelConfiguration.Builder()
                    .setAppName(appName)
                    .setPyroscopeEndpoint(endpoint)
                    .setProfileBaselineLabels(labels)
                    .setRootSpanOnly(getBoolean(cfg, "otel.pyroscope.root.span.only", true))
                    .setAddSpanName(getBoolean(cfg, "otel.pyroscope.add.span.name", true))
                    .setAddProfileURL(getBoolean(cfg, "otel.pyroscope.add.profile.url", true))
                    .setAddProfileBaselineURLs(getBoolean(cfg, "otel.pyroscope.add.profile.baseline.url", true))
                    .setOptimisticTimestamps(getBoolean(cfg, "otel.pyroscope.optimistic.timestamps", true))
                    .build();
            return tpBuilder.addSpanProcessor(
                    new PyroscopeOtelSpanProcessor(
                            pyroOtelConfig
                    ));
        });

    }

}