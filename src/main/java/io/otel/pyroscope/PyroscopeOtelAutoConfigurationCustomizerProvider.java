package io.otel.pyroscope;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;

@AutoService(AutoConfigurationCustomizerProvider.class)
public class PyroscopeOtelAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {
    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer((tpBuilder, cfg) -> {
            boolean startProfiling = cfg.getBoolean("otel.pyroscope.start.profiling", false);
            if (startProfiling) {
                PyroscopeAgent.start(Config.build());
            }
            return tpBuilder.addSpanProcessor(
                    new PyroscopeOtelSpanProcessor(
                            new PyroscopeOtelConfiguration.Builder()
                                    .setAppName(cfg.getString("otel.pyroscope.app.name"))
                                    .setPyroscopeEndpoint(cfg.getString("otel.pyroscope.endpoint"))
                                    .setRootSpanOnly(cfg.getBoolean("otel.pyroscope.root.span.only", true))
                                    .setAddSpanName(cfg.getBoolean("otel.pyroscope.add.span.name", false))
                                    .setAddProfileURL(cfg.getBoolean("otel.pyroscope.add.profile.url", false))
                                    .setAddProfileBaselineURLs(cfg.getBoolean("otel.pyroscope.add.profile.baseline.url", false))
                                    .build()
                    ));
        });
    }

}