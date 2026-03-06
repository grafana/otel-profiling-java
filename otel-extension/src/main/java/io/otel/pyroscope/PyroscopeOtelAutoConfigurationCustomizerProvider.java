package io.otel.pyroscope;


import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.pyroscope.javaagent.api.ProfilerApiHolder;

import static io.otel.pyroscope.OtelCompat.getBoolean;


public class PyroscopeOtelAutoConfigurationCustomizerProvider
        implements AutoConfigurationCustomizerProvider {


    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        BootstrapApiInjector.ensureInjected();

        autoConfiguration.addTracerProviderCustomizer((tpBuilder, cfg) -> {
            ProfilerApiInteraction.ensureProfilerApiSet();

            boolean startProfiling = getBoolean(cfg, "otel.pyroscope.start.profiling", true);
            if (startProfiling) {
                ProfilerApiHolder.INSTANCE.get().startProfiling();
            }

            PyroscopeOtelConfiguration pyroOtelConfig = new PyroscopeOtelConfiguration.Builder()
                    .setRootSpanOnly(getBoolean(cfg, "otel.pyroscope.root.span.only", true))
                    .setAddSpanName(getBoolean(cfg, "otel.pyroscope.add.span.name", true))
                    .build();

            return tpBuilder.addSpanProcessor(
                    new PyroscopeOtelSpanProcessor(pyroOtelConfig));
        });
    }
}
