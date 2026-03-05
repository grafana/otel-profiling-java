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
            final boolean DEBUG = Boolean.getBoolean("pyroscope.otel.debug"); // do nott use PyroscopeOtelDebug
            try {
                ClassLoader cl = hookedClass.getClassLoader();
                if (DEBUG) System.out.println("Instrumentation: PyroscopeAgent.start() hooked!");
                if (DEBUG) System.out.println("Instrumentation: PyroscopeAgent classloader: " + cl.getClass().getName());

                // Constructed at runtime to avoid shadow jar string relocation
                String implClassName = String.join(".", "io", "pyroscope", "javaagent", "ProfilerSdk");

                // Load and instantiate ProfilerSdk from the app classloader.
                Class<?> implClass = cl.loadClass(implClassName);
                java.lang.reflect.Constructor<?> ctor = implClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                // Cast to ProfilerApi — works because both this advice code and ProfilerSdk
                // resolve ProfilerApi from the bootstrap classloader (where it was injected).
                io.pyroscope.javaagent.api.ProfilerApi bridge =
                    (io.pyroscope.javaagent.api.ProfilerApi) ctor.newInstance();
                io.pyroscope.javaagent.api.ProfilerApiHolder.INSTANCE.set(bridge);
                if (DEBUG) System.out.println("Instrumentation: Set ProfilerApiHolder.INSTANCE from " + cl.getClass().getName());
            } catch (Exception e) {
                if (DEBUG) e.printStackTrace(System.out);
                if (DEBUG) System.out.println("Instrumentation: FAILED to hook ProfilerSdk: " + e);
            } catch (Throwable e) {
                if (DEBUG) e.printStackTrace(System.out);
                if (DEBUG) System.out.println("Instrumentation: FAILED to hook ProfilerSdk: " + e);
            }
        }
    }
}
