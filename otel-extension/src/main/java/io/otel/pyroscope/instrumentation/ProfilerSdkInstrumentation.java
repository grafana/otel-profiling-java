package io.otel.pyroscope.instrumentation;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Instrumentation that hooks PyroscopeAgent.start() to capture a ProfilerSdk
 * instance from the same classloader that loaded PyroscopeAgent (e.g. Spring Boot's
 * custom classloader) and register it as the active profiling bridge.
 *
 * NOTE: Class name strings are constructed via String.join to prevent the shadow
 * jar relocator from matching and renaming them. The relocator matches string
 * constants like "io.pyroscope.*" and would change them to
 * "io.otel.pyroscope.shadow.*", breaking type matchers.
 */
public class ProfilerSdkInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        // String.join to prevent shadow jar relocator from renaming "io.pyroscope" prefix
        return named(String.join(".", "io", "pyroscope", "javaagent", "PyroscopeAgent"));
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
            named("start"),
            this.getClass().getName() + "$StartAdvice"
        );
    }

    // todo do not use  instrumentation at all, just put this hook into the pyroscope-java itself with a try-catch
    // This class should have as little dependencies as possible, only ProfilerSdk and ProfilerApiHolder preferably
    @SuppressWarnings("unused")
    public static class StartAdvice {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Origin Class<?> hookedClass) {

        }
    }
}
