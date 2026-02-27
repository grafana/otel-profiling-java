# otel-profiling-java Examples

These examples demonstrate different ways to integrate Pyroscope profiling with OpenTelemetry in a Java application.

Each example runs a simple Spring Boot app that exposes a `/fibonacci?n=40` endpoint performing intentionally CPU-intensive recursive work, making profiling data easy to observe.

## Examples

### `with-otel-extension`

Uses the **OpenTelemetry Java agent** with `pyroscope-otel-javaagent-extension.jar` loaded as an OTel extension:

- The app itself has **zero Pyroscope or OTel code** — it's plain Spring Boot
- The OTel Java agent auto-instruments HTTP requests and creates spans automatically
- The `pyroscope-otel-javaagent-extension.jar` extension registers itself via the OTel SPI, auto-starts the Pyroscope profiler, and wires `PyroscopeOtelSpanProcessor` to link profiling data to trace spans
- Configured entirely via environment variables

JVM startup looks like:
```
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=pyroscope-otel-javaagent-extension.jar \
     -jar app.jar
```

### `with-otel-library`

Uses the **Pyroscope agent** and **OpenTelemetry SDK** as compile-time library dependencies:

- The app starts `PyroscopeAgent` programmatically in `OtelConfig.java`
- The app builds an `OpenTelemetrySdk` with `PyroscopeOtelSpanProcessor` added and registers it globally
- `WorkController.java` creates spans manually (no Java agent for auto-instrumentation)
- No `-javaagent` flags at startup — everything is bundled in the fat jar

JVM startup looks like:
```
java -jar app.jar
```

### `with-otel-extension-manual-start`

Uses the **OpenTelemetry Java agent** with `pyroscope-otel-javaagent-extension.jar` as an OTel extension, but starts the Pyroscope profiler **programmatically from Java code**:

- The app depends on `io.pyroscope:agent` as a compile-time library
- `App.java` calls `PyroscopeAgent.start(...)` explicitly before Spring starts
- The OTel Java agent auto-instruments HTTP requests and creates spans automatically
- The `pyroscope-otel-javaagent-extension.jar` extension is loaded with `OTEL_PYROSCOPE_START_PROFILING=false` so it does **not** auto-start the profiler
- The extension discovers the already-running profiler via `OtelProfilerSdkBridge` (system ClassLoader reflection) and wires `PyroscopeOtelSpanProcessor` for trace-profile correlation

JVM startup looks like:
```
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=pyroscope-otel-javaagent-extension.jar \
     -jar app.jar
```

This pattern is useful when you need full control over `PyroscopeAgent` configuration (custom labels, events, upload intervals, etc.) while still benefiting from OTel auto-instrumentation and automatic trace-profile linking.

## Prerequisites

- Docker and Docker Compose
- Java 11+ and Gradle (for the build step below)

## Running

> **Note:** The `pyroscope-otel` jar is not yet published to Maven Central. You must build it locally first.
> See the TODO comments in the Dockerfiles — once published, this step will not be required.

**Step 1:** From the **repository root**, build both jars:
```bash
./gradlew :otel-extension:shadowJar :lib:jar
```
This produces `otel-extension/build/libs/pyroscope-otel-javaagent-extension.jar` and `lib/build/libs/pyroscope-otel.jar`.

**Step 2:** From the **`examples/` directory**, start all containers:
```bash
docker-compose up --build
```

This starts:
- **Pyroscope** at `http://localhost:4040`
- **Grafana** at `http://localhost:3000`
- **otel-extension-example** at `http://localhost:8080`
- **otel-library-example** at `http://localhost:8081`
- **otel-extension-manual-start-example** at `http://localhost:8082`

**Step 3:** Generate some load to produce profiling data:
```bash
./loadgen.sh
```

**Step 4:** Open the Pyroscope UI at `http://localhost:4040` and select an application to view CPU flamegraphs.

## Configuration

| Environment Variable | Default | Description |
|---|---|---|
| `PYROSCOPE_SERVER_ADDRESS` | `http://localhost:4040` | Pyroscope server URL |
| `PYROSCOPE_APPLICATION_NAME` | example-specific | Application name shown in Pyroscope UI |
| `PYROSCOPE_FORMAT` | `jfr` | Profiling data format (extension examples only) |
| `OTEL_SERVICE_NAME` | example-specific | Service name in OTel (extension examples only) |
| `OTEL_PYROSCOPE_START_PROFILING` | `true` | Set to `false` to prevent the extension from auto-starting the profiler |
| `OTEL_TRACES_EXPORTER` | `logging` | Traces exporter (logging prints to stdout) |

For more information, see the [Grafana Pyroscope Java span profiles documentation](https://grafana.com/docs/pyroscope/latest/configure-client/trace-span-profiles/java-span-profiles/).
