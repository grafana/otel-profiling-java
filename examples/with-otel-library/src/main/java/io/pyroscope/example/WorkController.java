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
    private final CpuBurner cpuBurner;

    public WorkController(FibonacciService fibonacciService, CpuBurner cpuBurner) {
        this.fibonacciService = fibonacciService;
        this.cpuBurner = cpuBurner;
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
            return "fibonacci(" + n + ") = " + result + " spanId=" + span.getSpanContext().getSpanId();
        } finally {
            span.end();
        }
    }

    @GetMapping("/child-spans")
    public String childSpans() {
        Tracer tracer = GlobalOpenTelemetry.getTracer("otel-library-example", "1.0");
        Span span = tracer.spanBuilder("child-spans").startSpan();
        try (Scope scope = span.makeCurrent()) {
            Span child1 = tracer.spanBuilder("child1").startSpan();
            try (Scope s1 = child1.makeCurrent()) {
                burnChild1();
            } finally {
                child1.end();
            }

            Span child2 = tracer.spanBuilder("child2").startSpan();
            try (Scope s2 = child2.makeCurrent()) {
                burnChild2();
            } finally {
                child2.end();
            }

            return "spanId=" + span.getSpanContext().getSpanId();
        } finally {
            span.end();
        }
    }

    private void burnChild1() {
        cpuBurner.burnFor(1_000);
    }

    private void burnChild2() {
        cpuBurner.burnFor(2_000);
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
