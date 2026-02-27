package io.pyroscope.example;

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
     * Computes Fibonacci(n) recursively. The OTel Java agent automatically creates
     * a span for this HTTP request. The pyroscope-otel extension's PyroscopeOtelSpanProcessor
     * links the profiling data to the span ID via OtelProfilerSdkBridge, which communicates
     * with the PyroscopeAgent started in App.java across the classloader boundary.
     */
    @GetMapping("/fibonacci")
    public String fibonacci(@RequestParam(defaultValue = "40") int n) {
        long result = fibonacciService.compute(n);
        return "fibonacci(" + n + ") = " + result;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
