package io.otel.pyroscope;

/**
 * Debug logging for the Pyroscope OTel extension.
 * Disabled by default. Enable at runtime with {@code -Dpyroscope.otel.debug=true}.
 */
public class PyroscopeOtelDebug {
    public static final boolean DEBUG = Boolean.getBoolean("pyroscope.otel.debug");

    public static void log(String msg) {
        if (DEBUG) {
            System.out.println("[pyroscope-otel] " + msg);
        }
    }

    public static void log(String msg, Throwable t) {
        if (DEBUG) {
            System.out.println("[pyroscope-otel] " + msg);
            t.printStackTrace(System.out);
        }
    }
}
