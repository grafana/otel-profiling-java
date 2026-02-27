package io.pyroscope.example;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkController {

    private final FibonacciService fibonacciService;

    public WorkController(FibonacciService fibonacciService) {
        this.fibonacciService = fibonacciService;
    }

    /**
     * Computes Fibonacci(n) recursively. A span is created manually using the OTel SDK
     * (no Java agent is present in this example). PyroscopeOtelSpanProcessor.onStart()
     * picks up the span and annotates the profiling data with the span ID, linking
     * the CPU profile to this trace span in the Pyroscope UI.
     */
    @GetMapping("/fibonacci")
    public String fibonacci(@RequestParam(defaultValue = "40") int n) {
        Tracer tracer = GlobalOpenTelemetry.getTracer("otel-library-example", "1.0");
        Span span = tracer.spanBuilder("fibonacci").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            long result = fibonacciService.compute(n);
            return "fibonacci(" + n + ") = " + result;
        } finally {
            span.end();
        }
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
