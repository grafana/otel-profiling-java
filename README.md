## Java OpenTelemetry tracing integration

Pyroscope can integrate with distributed tracing systems supporting [**OpenTelemetry**](https://opentelemetry.io/docs/instrumentation/java/getting-started/) standard which allows you to
link traces with the profiling data, and find specific lines of code related to a performance issue.


* Because of how sampling profilers work, spans shorter than the sample interval may not be captured. By default pyroscope CPU profiler probes stack traces 100 times per second, meaning that spans shorter than 10ms may not be captured.


Java code can be easily instrumented with otel-profiling-java package -
a `OpenTelemetry` implementation, that annotates profiling data with span IDs which makes it possible to filter
out profile of a particular trace span in Pyroscope.

Visit [docs](https://grafana.com/docs/pyroscope/latest/configure-client/trace-span-profiles/java-span-profiles/) page for usage and configuration documentation.

## Examples

Check out the [examples](https://github.com/grafana/pyroscope/tree/main/examples/tracing/tempo) directory in our repository to
find a complete example application that demonstrates tracing integration features and learn more 🔥
