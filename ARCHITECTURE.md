# Cross-Classloader Profiler Communication

## Problem

The Pyroscope profiling agent and the OpenTelemetry Java agent extension need to share state for **span-profile correlation** — linking profiling samples to trace spans by span ID.

The OTel Java agent loads extensions in an isolated `ExtensionClassLoader`, while the Pyroscope agent runs in the system (app) classloader. These classloaders have no parent-child relationship:

```
Bootstrap CL
├── Platform CL
│   └── Agent CL (OTel Java agent)
│       └── ExtensionClassLoader (pyroscope-otel extension)
└── App CL / System CL
    ├── pyroscope-java agent (loaded via -javaagent or -classpath)
    └── LaunchedURLClassLoader (Spring Boot fat jar)
        └── application code, pyroscope-java (when used as library)
```

The core problem is that both the otel extension and the app classloader each had their own copy of `ProfilerApi`. Even though the class has the same fully-qualified name in both classloaders, the JVM treats them as **different types** — casting between them causes `ClassCastException`. This made it impossible for the extension to obtain a typed reference to the `ProfilerSdk` instance running in the app classloader.

The only classloader visible to **all** branches of the hierarchy is the **bootstrap classloader**.

## Previous Approach

Before this change, the otel extension used `OtelProfilerSdkBridge` — a reflection-based wrapper that called every `ProfilerSdk` method via `getDeclaredMethod().invoke()`. Because the extension couldn't cast to `ProfilerApi` across classloader boundaries, it had to discover the `ProfilerSdk` instance in the app classloader via `ClassLoader.getSystemClassLoader().loadClass(...)` and invoke all methods reflectively. Class name strings were Base64-encoded to prevent the shadow jar relocator from renaming them. The span processor had two separate code paths: one for the reflection bridge (when the pyroscope agent was present) and one for direct `AsyncProfiler` calls (fallback). No bootstrap classloader injection existed, and there was no way for the agent to "publish" its `ProfilerSdk` instance to the extension without reflection.

## Solution

This change introduces three new API classes that are injected into the bootstrap classloader at startup, plus a publish mechanism where the agent stores its `ProfilerSdk` instance into a shared holder at profiler start time:

| Class | Role |
|-------|------|
| `ProfilerApi` | Interface: `setTracingContext()`, `startProfiling()`, `registerConstant()`, etc. |
| `ProfilerApiHolder` | Static `AtomicReference<ProfilerApi>` — the JVM-wide rendezvous point |
| `ProfilerScopedContext` | Companion interface for scoped label management (deprecated) |

Once on the bootstrap classloader, **all** classloaders (Extension CL, App CL, any custom CL) resolve the same `ProfilerApiHolder` class with the same static `INSTANCE` field. The pyroscope agent publishes its `ProfilerSdk` instance into `ProfilerApiHolder.INSTANCE` at start time, and the otel extension reads it — no reflection needed. A direct cast `(ProfilerApi) sdkInstance` is safe because both sides resolve `ProfilerApi` from the same bootstrap classloader.

## Build-Time Flow

The 3 API classes live in `pyroscope-java/agent/src/main/java/io/pyroscope/javaagent/api/`. At build time:

1. **`bootstrapApiJar`** task compiles and packages only these 3 classes into `pyroscope-bootstrap.jar`
2. **`copyBootstrapToResources`** copies it as `pyroscope-bootstrap.jar.bin` into resources (`.bin` extension prevents the shadow jar plugin from merging/relocating its contents)
3. The agent shadow jar (`pyroscope.jar`) embeds `pyroscope-bootstrap.jar.bin` as a resource
4. The otel extension depends on `io.pyroscope:agent` — when the shadow jar plugin builds the extension, it includes the `pyroscope-bootstrap.jar.bin` resource from the agent dependency
5. The otel extension shadow jar **excludes** the 3 API `.class` files (they are injected via bootstrap, not loaded from the extension jar)

## Runtime Flow

Both the pyroscope-java agent and the otel extension independently inject the bootstrap API classes. **Whichever runs first performs the injection; the second is a no-op** since the classes are already on the bootstrap classloader.

### Scenario A: OTel Agent First (typical)

```
1. OTel Java agent starts → loads pyroscope-otel extension
2. Extension AutoConfigurationCustomizerProvider runs:
   a. BootstrapApiInjector.ensureInjected()
      → extracts pyroscope-bootstrap.jar.bin → injects into bootstrap CL
   b. Seeds ProfilerApiHolder.INSTANCE with relocated fallback ProfilerSdk
   c. Tries to load ProfilerSdk from system classloader
      → if pyroscope agent is on classpath, replaces holder with real instance
   d. Calls ProfilerApiHolder.INSTANCE.get().startProfiling()
   e. Registers PyroscopeOtelSpanProcessor
3. (Optional) Pyroscope agent premain runs later:
   a. BootstrapApiInjector.inject() → classes already on bootstrap, no-op
   b. publishProfilerApi() → sets ProfilerSdk into holder
```

### Scenario B: Pyroscope Agent First

```
1. Pyroscope agent premain runs:
   a. BootstrapApiInjector.inject() → injects into bootstrap CL
   b. publishProfilerApi() → sets ProfilerSdk into holder
2. OTel Java agent starts → loads extension
3. Extension AutoConfigurationCustomizerProvider runs:
   a. BootstrapApiInjector.ensureInjected() → already injected, no-op
   b. Seed with fallback → fails (holder already set)
   c. Tries system classloader → finds real ProfilerSdk, replaces holder
   d. Registers PyroscopeOtelSpanProcessor
```

### Scenario C: OTel Extension Only (no Pyroscope agent)

```
1. Extension injects bootstrap API classes
2. Seeds holder with relocated fallback ProfilerSdk (bundled in extension jar)
3. System classloader lookup fails → continues with fallback
4. Span processor uses the fallback ProfilerSdk
```

## Span-Profile Correlation

`PyroscopeOtelSpanProcessor` is registered as an OTel `SpanProcessor`:

- **`onStart()`**: Calls `api.setTracingContext(spanId, spanName)` — the profiler tags subsequent samples with this span ID
- **`onEnd()`**: Calls `api.setTracingContext(0, 0)` — clears the tracing context

The profiler embeds span IDs into profiling samples, allowing Grafana to link traces to flame graphs.

## Key Files

**pyroscope-java:**
- `agent/src/main/java/io/pyroscope/javaagent/api/ProfilerApi.java` — shared interface
- `agent/src/main/java/io/pyroscope/javaagent/api/ProfilerApiHolder.java` — cross-CL rendezvous
- `agent/src/main/java/io/pyroscope/javaagent/BootstrapApiInjector.java` — agent-side injection
- `agent/src/main/java/io/pyroscope/javaagent/PyroscopeAgent.java` — premain entry point

**otel-profiling-java:**
- `otel-extension/src/main/java/io/otel/pyroscope/BootstrapApiInjector.java` — extension-side injection
- `otel-extension/src/main/java/io/otel/pyroscope/PyroscopeOtelAutoConfigurationCustomizerProvider.java` — initialization
- `otel-extension/src/main/java/io/otel/pyroscope/PyroscopeOtelSpanProcessor.java` — span-profile linking
- `otel-extension/src/main/java/io/pyroscope/javaagent/ProfilerSdkFactory.java` — fallback ProfilerSdk factory
