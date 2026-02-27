package io.pyroscope.example;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Logs each span's name and ID to stdout as it starts and ends.
 * Useful for verifying that spans are being created and picked up by PyroscopeOtelSpanProcessor.
 */
public class LoggingSpanProcessor implements SpanProcessor {

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        System.out.printf("[span start] name=%s id=%s traceId=%s%n",
                span.getName(),
                span.getSpanContext().getSpanId(),
                span.getSpanContext().getTraceId());
    }

    @Override
    public void onEnd(ReadableSpan span) {
        System.out.printf("[span end]   name=%s id=%s duration=%dms%n",
                span.getName(),
                span.getSpanContext().getSpanId(),
                span.toSpanData().getEndEpochNanos() / 1_000_000 - span.toSpanData().getStartEpochNanos() / 1_000_000);
    }
}
