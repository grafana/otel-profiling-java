package io.otel.pyroscope.instrumentation;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Instrumentation that hooks PyroscopeAgent.start() to capture
 * a ProfilerSdk instance from the same classloader that loaded PyroscopeAgent
 * (e.g. Spring Boot's custom classloader) and register it as the active profiler.
 *
 * NOTE: Class name strings are constructed via String.join to prevent the shadow
 * jar relocator from matching and renaming them. The relocator matches string
 * constants like "io.pyroscope.*" and would change them to
 * "io.otel.pyroscope.shadow.*", breaking type matchers.
 */
public class ProfilerSdkInstrumentation implements TypeInstrumentation {

    // Constructed at runtime to avoid shadow jar relocation of "io.pyroscope" prefix.
    private static final String PYROSCOPE_PKG = String.join(".", "io", "pyroscope", "javaagent");

    // io.pyroscope.javaagent.PyroscopeAgent
    private static final String PYROSCOPE_AGENT_CLASS = PYROSCOPE_PKG + ".PyroscopeAgent";

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named(PYROSCOPE_AGENT_CLASS);
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
            named("start"),
            this.getClass().getName() + "$StartAdvice"
        );
    }

    @SuppressWarnings("unused")
    public static class StartAdvice {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Origin Class<?> hookedClass) {
            try {
                ClassLoader cl = hookedClass.getClassLoader();
                System.out.println("[pyroscope-otel] Instrumentation: PyroscopeAgent.start() hooked!");
                System.out.println("[pyroscope-otel] Instrumentation: PyroscopeAgent classloader: " + cl.getClass().getName());

                // Constructed at runtime to avoid shadow jar string relocation
                // io.pyroscope.javaagent.ProfilerSdk
                String sdkClassName = String.join(".", "io", "pyroscope", "javaagent", "ProfilerSdk");

                // Load and instantiate ProfilerSdk from the app classloader.
                // ProfilerSdk implements ProfilerApi (injected as helper into this CL).
                Class<?> sdkClass = cl.loadClass(sdkClassName);
                java.lang.reflect.Constructor<?> ctor = sdkClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                // Cast to ProfilerApi — works because both ProfilerSdk and this advice code
                // resolve ProfilerApi from the same classloader (app CL, where it was injected).
                io.pyroscope.agent.api.ProfilerApi sdk =
                    (io.pyroscope.agent.api.ProfilerApi) ctor.newInstance();
                io.pyroscope.agent.api.ProfilerApi.Holder.INSTANCE.set(sdk);
                System.out.println("[pyroscope-otel] Instrumentation: Set ProfilerApi.Holder.INSTANCE from " + cl.getClass().getName());
            } catch (Exception e) {
                System.out.println("[pyroscope-otel] Instrumentation: FAILED to hook ProfilerSdk: " + e);
                e.printStackTrace();
            }
        }
    }
}
