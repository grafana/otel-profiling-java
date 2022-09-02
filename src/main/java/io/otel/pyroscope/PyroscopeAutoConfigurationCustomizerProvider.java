package io.otel.pyroscope;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

@AutoService(AutoConfigurationCustomizerProvider.class)
public class PyroscopeAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {
    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer((tpBuilder, cfg) -> tpBuilder.addSpanProcessor(
                new PyroscopeSpanProcessor(
                        new PyroscopeConfiguration.Builder()
                                .setAppName(cfg.getString("otel.pyroscope.app.name"))
                                .setPyroscopeEndpoint(cfg.getString("otel.pyroscope.endpoint"))
                                .setRootSpanOnly(cfg.getBoolean("otel.pyroscope.root.span.only", true))
                                .setAddSpanName(cfg.getBoolean("otel.pyroscope.add.span.name", true))
                                .setAddProfileURL(cfg.getBoolean("otel.pyroscope.add.profile.url", true))
                                .setAddProfileBaselineURLs(cfg.getBoolean("otel.pyroscope.add.profile.baseline.url", true))
                                .build()
                )));
    }

}