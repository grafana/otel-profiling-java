package io.pyroscope.javaagent.api;

import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Shared profiling interface injected into the app classloader by the OTel extension's
 * InstrumentationModule. This interface MUST be kept in sync with the copy in pyroscope-java
 * (same package, same method signatures) — the JVM must consider them the same type across
 * classloaders for the cross-classloader cast to work. Do not modify without updating both repos.
 */
public interface ProfilerApi {
    void startProfiling();

    boolean isProfilingStarted();

    @Deprecated
    @NotNull ProfilerScopedContext createScopedContext(@NotNull Map<@NotNull String, @NotNull String> labels);

    void setTracingContext(long spanId, long spanName);

    long registerConstant(String constant);
}
