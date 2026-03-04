package io.pyroscope.javaagent.api;

import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

/**
 * Injected into the app classloader by the OTel extension's InstrumentationModule.
 * This interface MUST be kept in sync with the copy in pyroscope-java (same package, same
 * method signatures) — do not modify without updating both repos.
 */
public interface ProfilerScopedContext {
    void forEachLabel(@NotNull BiConsumer<@NotNull String, @NotNull String> consumer);
    void close();
}
