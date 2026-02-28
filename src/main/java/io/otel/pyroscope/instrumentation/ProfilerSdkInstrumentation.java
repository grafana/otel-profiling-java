package io.otel.pyroscope.instrumentation;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.reflect.Constructor;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

/**
 * Instrumentation that hooks PyroscopeAgent.start(Options) to capture
 * a ProfilerSdk instance from the same classloader that loaded PyroscopeAgent
 * (e.g. Spring Boot's custom classloader) and register it as the active profiler.
 *
 * This covers the case where pyroscope-java is loaded by a custom classloader
 * rather than the system classloader, so the ProfilerSdk cannot be found via
 * ClassLoader.getSystemClassLoader().
 */
public class ProfilerSdkInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("io.pyroscope.javaagent.PyroscopeAgent");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
            named("start").and(takesArgument(0, named("io.pyroscope.javaagent.PyroscopeAgent$Options"))),
            this.getClass().getName() + "$StartAdvice"
        );
    }

    @SuppressWarnings("unused")
    public static class StartAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Origin Class<?> hookedClass) {
            // Use the classloader that loaded PyroscopeAgent (e.g. Spring Boot CL)
            // to instantiate ProfilerSdk from the same classloader.
            // ProfilerSdk implements io.pyroscope.agent.api.ProfilerApi which is injected
            // into the bootstrap classloader, so the cast works across classloaders.
            // One-time reflection for object creation only; all subsequent calls go
            // via the ProfilerApi interface cast (no reflection).
            try {
                ClassLoader cl = hookedClass.getClassLoader();
                Class<?> sdkClass = cl.loadClass("io.pyroscope.javaagent.ProfilerSdk");
                Constructor<?> ctor = sdkClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                io.pyroscope.agent.api.ProfilerApi sdk =
                    (io.pyroscope.agent.api.ProfilerApi) ctor.newInstance();
                io.otel.pyroscope.PyroscopeOtelSpanProcessor.PROFILER.set(sdk);
            } catch (Exception e) {
                // ignore — will use existing default profiler
            }
        }
    }
}
