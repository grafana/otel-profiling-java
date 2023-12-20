## Java OpenTelemetry tracing integration

Pyroscope can integrate with distributed tracing systems supporting [**OpenTelemetry**](https://opentelemetry.io/docs/instrumentation/java/getting-started/) standard which allows you to
link traces with the profiling data, and find specific lines of code related to a performance issue.


* Because of how sampling profilers work, spans shorter than the sample interval may not be captured. By default pyroscope CPU profiler probes stack traces 100 times per second, meaning that spans shorter than 10ms may not be captured.


Java code can be easily instrumented with otel-pyroscope package -
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
  .setAddSpanName(true)
  .setRootSpanOnly(true)
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
- `otel.pyroscope.root.span.only` - Boolean flag. When enabled, the tracer will annotate only the first span created locally
(the root span), but the profile will include samples of all the nested spans. This may be helpful in case if the trace
consists of multiple spans shorter than 10ms and profiler can't collect and annotate samples properly. Default: `true`.
- `tel.pyroscope.add.span.name` - Boolean flag. Controls whether the span name added to profile labels. Default: `true`.

## Examples

Check out the [examples](https://github.com/grafana/pyroscope/tree/main/examples/tracing/tempo) directory in our repository to
find a complete example application that demonstrates tracing integration features and learn more 🔥
