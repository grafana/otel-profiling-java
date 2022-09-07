
## OpenTelemetry support

Pyroscope can integrate with distributed tracing systems supporting [**OpenTelemetry**](https://opentelemetry.io/docs/instrumentation/java/getting-started/) standard which allows you to
link traces with the profiling data, and find specific lines of code related to a performance issue.


* Because of how sampling profilers work, spans shorter than the sample interval may not be captured. By default pyroscope CPU profiler probes stack traces 100 times per second, meaning that spans shorter than 10ms may not be captured.


Java code can be easily instrumented with [otelpyroscope](https://github.com/pyroscope-io/pyroscope-java/tree/main/otelpyroscope) package -
a `OpenTelemetry` implementation, that annotates profiling data with span IDs which makes it possible to filter
out profile of a particular trace span in Pyroscope:

### Running as otel-java-instrumentation extension
Download latest `opentelemetry-javaagent.jar` and `pyroscope-otel.jar`
```bash
java -jar ./build/libs/rideshare-1.0-SNAPSHOT.jar \
    -javaagent:./opentelemetry-javaagent.jar \
    -Dotel.javaagent.extensions=./pyroscope-otel.jar \
    -Dotel.pyroscope.start.profiling=true \
    -Dpyroscope.application.name=ride-sharing-app-java-instrumentation  \
    -Dpyroscope.format=jfr \
    -Dpyroscope.profiler.event=itimer \
    -Dpyroscope.server.address=$PYROSCOPE_SERVER_ADDRESS \
    # rest of your otel-java-instrumentation configuration
```

### Running as manually configured otel

```javascript
// Add latest pyroscope otel depedency
implementation("io.pyroscope:otel:0.10.1.1")
```

Now we can create and configure the tracer provider:
```javascript
// obtain SdkTracerProviderBuilder
SdkTracerProviderBuilder tpBuilder = ... 

// Add PyroscopeOtelSpanProcessor to SdkTracerProviderBuilder
PyroscopeOtelConfiguration pyroscopeTelemetryConfig = new PyroscopeOtelConfiguration.Builder()
  .setAppName("simple.java.app." + EventType.ITIMER.id)
  .setPyroscopeEndpoint(System.getenv("PYROSCOPE_PROFILE_URL"))
  .setAddProfileURL(true)
  .setAddSpanName(true)
  .setRootSpanOnly(true)
  .setAddProfileBaselineURLs(true)
  .build();
tpBuilder.addSpanProcessor(new PyroscopeOtelSpanProcessor(pyroscopeOtelConfig));
```

Now that we set up the tracer, we can create a new trace from anywhere:
```javascript
Span span = tracer.spanBuilder("findNearestVehicle").startSpan();
try (Scope s = span.makeCurrent()){
    // Your code goes here.
} finally {
    span.end();
}
```

## Configuration options
- `otel.pyroscope.start.profiling` - Boolean flag to start PyroscopeAgent. Set to false if you want to start the PyroscopeAgent manually. Default: `true`.
- `otel.pyroscope.app.name` - Application name for profiler/baseline urls. if `otel.pyroscope.start.profiling=true` then it is ignored and app name is taken from `pyroscope.application.name` or from the generated name if the pyroscope app name was not configured.  
- `otel.pyroscope.endpoint` - Pyroscope server address url. if `otel.pyroscope.start.profiling=true` then it is ignored and server address is taken from `pyroscope.server.address` or set to `http://localhost:4040` by pyroscope configuration
- `otel.pyroscope.root.span.only` - Boolean flag. When enabled, the tracer will annotate only the first span created locally
(the root span), but the profile will include samples of all the nested spans. This may be helpful in case if the trace
consists of multiple spans shorter than 10ms and profiler can't collect and annotate samples properly. Default: `true`.
- `tel.pyroscope.add.span.name` - Boolean flag. Controls whether the span name added to profile labels. Default: `true`.
automatically. If enabled, please make sure span names do not contain unique values, for example, a URL.  Otherwise, this can increase data cardinality and slow down queries.
- `otel.pyroscope.add.profile.url` - Boolean flag to attach pyroscope profile urls to spans. Default: true.
- `otel.pyroscope.add.profile.baseline.url` - Boolean flag to attach pyroscope profile diff/comparison urls to spans. Default: true.

## Baseline profiles

Collected profiles can be viewed in Pyroscope UI using FlameQL:
- `simple.java.app.itimer{profile_id="<spanID>"}` - Shows flamegraph for a particular span.
- `simple.java.app.itimer{span_name="ExampleSpan"}` - Shows aggregated profile for all spans named **ExampleSpan**.

For convenience, the tracer annotates profiled spans with extra attributes:
- `pyroscope.profile.id` - is set to span ID to indicate that profile was captured for a span.
- `pyroscope.profile.url` - contains the URL to the flamegraph in Pyroscope UI.
- `pyroscope.profile.baseline.url` - contains the URL to the baseline comparison view in Pyroscope UI.

A **baseline profile** is an aggregate of all span profiles. For example, consider two exemplars (the number here
replaces profiling data):
- `simple.java.app.itimer{region="us=east",env="dev",span_name="FetchData",profile_id="abc"}` 100
- `simple.java.app.itimer{region="us=east",env="dev",span_name="FetchData",profile_id="zyx"}` 200

Then, the baseline profile for each of them is:
- `simple.java.app.itimer{region="us=east",env="dev",span_name="FetchData"}` 300

## Examples

Check out the [examples](https://github.com/pyroscope-io/pyroscope/tree/main/examples/tracing/jaeger/java/rideshare) directory in our repository to
find a complete example application that demonstrates tracing integration features and learn more 🔥
- [Jaeger](https://github.com/pyroscope-io/pyroscope/tree/main/examples/tracing/jaeger/)