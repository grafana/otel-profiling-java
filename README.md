
## OpenTelemetry support

Pyroscope can integrate with distributed tracing systems supporting [**OpenTelemetry**](https://opentelemetry.io/docs/instrumentation/java/getting-started/) standard which allows you to
link traces with the profiling data, and find specific lines of code related to a performance issue.


* Because of how sampling profilers work, spans shorter than the sample interval may not be captured. By default pyroscope CPU profiler probes stack traces 100 times per second, meaning that spans shorter than 10ms may not be captured.


Java code can be easily instrumented with [otelpyroscope](https://github.com/pyroscope-io/pyroscope-java/tree/main/otelpyroscope) package -
a `OpenTelemetry` implementation, that annotates profiling data with span IDs which makes it possible to filter
out profile of a particular trace span in Pyroscope:

```javascript
// Add pyroscope otel depedency
implementation("io.pyroscope:otel:0.10.0")
```

Now we can create and configure the tracer provider:
```javascript
OpenTelemetrySdk sdkTelemetry = // Obtain OpenTelemetrySdk.

// Wrap OpenTelemetrySdk with PyroscopeTelemetry
PyroscopeTelemetry.Config pyroscopeTelemetryConfig = new PyroscopeTelemetry.Config.Builder()
  .setAppName("simple.java.app." + EventType.ITIMER.id)
  .setPyroscopeEndpoint(System.getenv("PYROSCOPE_PROFILE_URL"))
  .setAddProfileURL(true)
  .setAddSpanName(true)
  .setRootSpanOnly(true)
  .setAddProfileBaselineURLs(true)
  .build();
PyroscopeTelemetry pyroscopeTelemetry = new PyroscopeTelemetry(sdkTelemetry, pyroscopeTelemetryConfig);
GlobalOpenTelemetry.set(pyroscopeTelemetry);
```

Please notice the `setRootSpanOnly` option: when enabled, the tracer will annotate only the first span created locally
(the root span), but the profile will include samples of all the nested spans. This may be helpful in case if the trace
consists of multiple spans shorter than 10ms and profiler can't collect and annotate samples properly.

Another option that you may find useful is `setAddSpanName` that controls whether the span name added to profile labels
automatically.


If you enable `setAddSpanName` option, please make sure span names do not contain unique values, for example, a URL.
Otherwise, this can increase data cardinality and slow down queries.


Now that we set up the tracer, we can create a new trace from anywhere:
```javascript
Span span = tracer.spanBuilder("findNearestVehicle").startSpan();
try (Scope s = span.makeCurrent()){
    // Your code goes here.
} finally {
    span.end();
}

```

Collected profiles can be viewed in Pyroscope UI using FlameQL:
- `simple.java.app.itimer{profile_id="<spanID>"}` - Shows flamegraph for a particular span.
- `simple.java.app.itimer{span_name="ExampleSpan"}` - Shows aggregated profile for all spans named **ExampleSpan**.

For convenience, the tracer annotates profiled spans with extra attributes:
- `pyroscope.profile.id` - is set to span ID to indicate that profile was captured for a span.
- `pyroscope.profile.url` - contains the URL to the flamegraph in Pyroscope UI.
- `pyroscope.profile.baseline.url` - contains the URL to the baseline comparison view in Pyroscope UI.

## Baseline profiles

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