package io.otel.pyroscope;

import io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesBuilder;
import io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesConfigurer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

/**
 * Configures bootstrap classloader to include Pyroscope packages.
 */
public class PyroscopeBootstrapConfig implements BootstrapPackagesConfigurer {
    @Override
    public void configure(BootstrapPackagesBuilder bootstrapPackagesBuilder, ConfigProperties configProperties) {
        bootstrapPackagesBuilder.add("io.pyroscope");
    }
}
