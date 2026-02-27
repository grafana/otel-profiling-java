package io.pyroscope.example;

import org.springframework.stereotype.Service;

@Service
public class FibonacciService {

    /**
     * Intentionally naive recursive Fibonacci to generate meaningful CPU work
     * that is visible in profiling flamegraphs.
     */
    public long compute(int n) {
        if (n <= 1) return n;
        return compute(n - 1) + compute(n - 2);
    }
}
