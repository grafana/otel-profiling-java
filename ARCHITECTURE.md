# Cross-Classloader Profiler Communication

## Context
- pyroscope-java - aka pyroscope agent, aka `io.pyroscope:agent`, aka pyroscope.jar - a java fat jar library, vendors async-profiler, published to maven central and github releases. Can be used as library from maven central or as javaagent for autoinstrumentation or both.
- otel-profiling-java - aka pyroscope otel extension, aka `io.pyroscope:otel` - a SpanProcessor that listens for spans and annotates the spans with `pyroscope.profile.id` and profiles with span ids and span names - this enables the correlation between a span and a profile. The extension can be used as otel java agent extension (in this case it is loaded in a separate isolated classloader) and as a library by an app (can be in system classloader or custom classloader (for example spring boot)). The pyroscope otel extension vendors the `io.pyroscope:agent` (the whole profiler) into the `io.otel.pyroscope.shadow` package - to be able to start profiling  if no other profiler is found.

## Problem

Otel javaagent loads pyroscope extension in an isolated classloader. The Pyroscope agent may be loaded in the system class loader (if used as -javaagent or -classpath) or other isolated classloader (for example spring boot fat jar). Profiler needs to somehow coordinate between each other (use the same storage for span ids / labels, do not start second profiler), and it's hard to do due to classloader isolations. Especially in the case when the pyroscope agent is loaded from an insolated non system class loader (spring boot fat jar)



## Previous Approach
We had `ProfilerApi` interface and `ProfilerSdk` implementation in both classloaders. The otel extension attempts to insttantiate an instance of `ProfilerSdk` with reflection from system classloader. Uses reflection to invoke methods on the `ProfilerSdk`. This approach covers the case when the pyroscope agent is available on system classloader (-javaagent or -classpath), but not fat jars

## New Approach



We create a new class 
```java
public class ProfilerApiHolder {
    public static final AtomicReference<ProfilerApi> INSTANCE = new AtomicReference<>();
}
```

And modify the pyroscope agent to set INSTANCE value upon starting. This allows to cover a custom classloader case (for example spring boot fat jar classloader), when otel extension has no idea how to reach custom class loader.

The `ProfilerApiHolder` and `ProfilerApi` and unfortunately `ProfilerScopedContext` are injected into bootstrap classloader using `Instrumentation#appendToBootstrapClassLoaderSearch`. Injection happen as early as possible in two places in the agent `premain` and in the otel extension configuration entrypoint.

```java
class PyroscopeAgent {
    // ...
    // invoked after successful profiler start
    private static void publishProfilerApi(Logger logger) {
        ProfilerApiHolder.INSTANCE.set(new ProfilerSdk());
    }
```

This allows every party to use `ProfilerApi` interface with no reflection.

## Build-Time Flow
In the pyroscope agent at build time, we create an extra `jar` with the three mentioned classes and embed the jar with three classes as a resource to the pyroscope agent.

## Runtime Flow

1. Only otel extension is present

- Extension injects the classes to the bootstrap classloader
- Sees a null instance of `ProfilerApiHolder`
- Attempts to instantiate a `io.pyroscope.javaagent.ProfilerSdk` from system classloader, fails
- Sets an instance of the `io.otel.pyroscope.shadow.javaagent.ProfilerSdk` to the `ProfilerApiHolder` as fallback in order to use the vendored profiler(agent).
- Starts profiling (`publishProfilerApi` updates `ProfilerApiHolder.INSTANCE` with a new instance of the same class `io.otel.pyroscope.shadow.javaagent.ProfilerSdk`, just different object instance)
- The span processor uses the second instance of `io.otel.pyroscope.shadow.javaagent.ProfilerSdk` obtained from `ProfilerApiHolder` forever

2. Java agent is launched as  `-javaagent`, profiling started, otel extension is present

- Java agent injects the classes to the bootstrap classloader
- Java agents starts profiling, `publishProfilerApi` updates `ProfilerApiHolder.INSTANCE` with an instance of `io.pyroscope.javaagent.ProfilerSdk` 
- Extension injects the classes to the bootstrap again (no-op)
- Sees a non-null instance of `ProfilerApiHolder`, does not attempt to change it.
- The span processor uses `io.pyroscope.javaagent.ProfilerSdk`

3. Java agent is launched as `-javaagent`, but profiling not started, otel extension is present

- Java agent injects the classes to the bootstrap classloader
- Java agents does not start  profiling, `ProfilerApiHolder.INSTANCE` is still `null`
- Extension injects the classes to the bootstrap again (no-op)
- Sees a null instance of `ProfilerApiHolder`
- Instantiates `io.pyroscope.javaagent.ProfilerSdk` from system classloader and updates `ProfilerApiHolder.INSTANCE` instance with it
- Starts profiling (`publishProfilerApi` updates `ProfilerApiHolder.INSTANCE` with a new instance of the same class `io.pyroscope.javaagent.ProfilerSdk`, just different object instance)
- The span processor uses `io.pyroscope.javaagent.ProfilerSdk`

3. Java agent is used as classpath library(not as -javaagent), otel extension is present

- Extension injects the classes to the bootstrap 
- Sees a null instance of `ProfilerApiHolder`
- Instantiates `io.pyroscope.javaagent.ProfilerSdk` from system classloader and updates `ProfilerApiHolder.INSTANCE` instance with it
- Starts profiling (`publishProfilerApi` updates `ProfilerApiHolder.INSTANCE` with a new instance of the same class `io.pyroscope.javaagent.ProfilerSdk`, just different object instance)
- The span processor uses `io.pyroscope.javaagent.ProfilerSdk`


4. Java agent is used from fat jar, otel extension is present. << This was broken
- Extension injects the classes to the bootstrap classloader
- Sees a null instance of `ProfilerApiHolder`
- Attempts to instantiate a `io.pyroscope.javaagent.ProfilerSdk` from system classloader, fails
- Sets an instance of the `io.otel.pyroscope.shadow.javaagent.ProfilerSdk` to the `ProfilerApiHolder` as fallback in order to use the vendored profiler(agent).
- Does not start profiling with `otel.pyroscope.start.profiling=false`
- This is where this broken - previously otel extension had no way to find out about a profiler started from a fat jar classloader
- Next java agent from fat jar starts profiling from java code as a library.(`publishProfilerApi` updates `ProfilerApiHolder.INSTANCE` with a new instance of  `io.pyroscope.javaagent.ProfilerSdk`, swapping from the vendor/shadow type to the one from agent jar library. This is the fix.
- The span processor uses `io.pyroscope.javaagent.ProfilerSdk`
