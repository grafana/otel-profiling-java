package io.otel.pyroscope;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

public class OtelCompat {
    // For compat reasons
    // io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties.getBoolean(java.lang.String, boolean) is only since 1.15
    static boolean getBoolean(ConfigProperties cfg, String name, boolean defaultValue) {
        Boolean v = cfg.getBoolean(name);
        if (v == null) {
            return defaultValue;
        }
        return v;
    }

    // For compat reasons
    // io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties.getString(java.lang.String, java.lang.String) is only since 1.15
    static String getString(ConfigProperties cfg, String name, String defaultValue) {
        String v = cfg.getString(name);
        if (v == null) {
            return defaultValue;
        }
        return v;
    }
}
