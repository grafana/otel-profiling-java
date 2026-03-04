package io.pyroscope.javaagent.api;

import java.util.concurrent.atomic.AtomicReference;

/**
 * This class does NOT exist in pyroscope-java. It lives exclusively in otel-profiling-java
 * and is injected into the app classloader by the OTel extension's InstrumentationModule
 * (via {@code getAdditionalHelperClassNames()}).
 *
 * <p>Any modification to this class is a <b>breaking change</b> that requires a major release
 * of both pyroscope-java and otel-profiling-java, with release notes documenting the
 * incompatibility between old and new versions.
 */
public class ProfilerApiHolder {
    public static final AtomicReference<ProfilerApi> INSTANCE = new AtomicReference<>();
}
