package io.otel.pyroscope.instrumentation;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Base64;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Instrumentation that hooks PyroscopeAgent.start() to capture
 * a ProfilerSdk instance from the same classloader that loaded PyroscopeAgent
 * (e.g. Spring Boot's custom classloader) and register it as the active profiler.
 *
 * NOTE: Class name strings are Base64-encoded to prevent the shadow jar relocator
 * from renaming them. The relocator matches string constants like "io.pyroscope.*"
 * and would change them to "io.otel.pyroscope.shadow.*", breaking type matchers.
 */
public class ProfilerSdkInstrumentation implements TypeInstrumentation {

    // Base64 of "io.pyroscope.javaagent.PyroscopeAgent"
    private static final String PYROSCOPE_AGENT_CLASS =
        new String(Base64.getDecoder().decode("aW8ucHlyb3Njb3BlLmphdmFhZ2VudC5QeXJvc2NvcGVBZ2VudA=="));

    // Base64 of "io.pyroscope.javaagent.ProfilerSdk"
    private static final String PROFILER_SDK_CLASS =
        new String(Base64.getDecoder().decode("aW8ucHlyb3Njb3BlLmphdmFhZ2VudC5Qcm9maWxlclNkaw=="));

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
                System.out.println("[pyroscope-otel] Instrumentation: PyroscopeAgent classloader: " + cl);
                System.out.println("[pyroscope-otel] Instrumentation: PyroscopeAgent classloader class: " + cl.getClass().getName());

                // Base64 decode inline to avoid shadow jar string relocation
                // Decodes to: io.pyroscope.javaagent.ProfilerSdk
                String sdkClassName = new String(
                    Base64.getDecoder().decode("aW8ucHlyb3Njb3BlLmphdmFhZ2VudC5Qcm9maWxlclNkaw=="));

                // Load and instantiate ProfilerSdk from the app classloader
                Class<?> sdkClass = cl.loadClass(sdkClassName);
                System.out.println("[pyroscope-otel] Instrumentation: Loaded ProfilerSdk from classloader: " + sdkClass.getClassLoader());
                java.lang.reflect.Constructor<?> ctor = sdkClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object sdkInstance = ctor.newInstance();
                System.out.println("[pyroscope-otel] Instrumentation: Created ProfilerSdk instance: " + sdkInstance.getClass().getName());
                System.out.println("[pyroscope-otel] Instrumentation: ProfilerSdk interfaces: " + java.util.Arrays.toString(sdkInstance.getClass().getInterfaces()));

                // Store the instance in system properties — visible to all classloaders.
                // System.getProperties() returns a Hashtable<Object,Object> so it can store
                // arbitrary object references. The span processor reads this key to pick up
                // the app-classloader ProfilerSdk.
                System.getProperties().put("io.pyroscope.otel.profilerSdk", sdkInstance);
                System.out.println("[pyroscope-otel] Instrumentation: Stored ProfilerSdk in system properties");
            } catch (Exception e) {
                System.out.println("[pyroscope-otel] Instrumentation: FAILED to hook ProfilerSdk: " + e);
                e.printStackTrace();
            }
        }
    }
}
