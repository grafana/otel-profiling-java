package io.otel.pyroscope;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadableSpan;

import io.pyroscope.PyroscopeAsyncProfiler;
import io.pyroscope.labels.v2.Pyroscope;
import io.pyroscope.vendor.one.profiler.AsyncProfiler;

/**
 * ContextStorage wrapper that synchronizes OTel context with async-profiler's
 * native pthread thread-local storage on every context switch.
 *
 * This ensures profiling samples are correctly associated with spans,
 * even when work is executed on different threads via executors.
 */
public final class ProfilingContextStorage implements ContextStorage {
    private final ContextStorage delegate;
    private final AsyncProfiler asprof;

    public ProfilingContextStorage(ContextStorage delegate) {
        this.delegate = delegate;
        this.asprof = PyroscopeAsyncProfiler.getAsyncProfiler();
    }

    @Override
    public Scope attach(Context toAttach) {
        // 1. Extract span ID and name from the context being attached
        long spanId = 0L;
        long spanNameId = 0L;

        if (toAttach != null) {
            Span span = Span.fromContext(toAttach);
            SpanContext spanContext = span.getSpanContext();

            if (spanContext.isValid()) {
                spanId = parseHexSpanId(spanContext.getSpanId());

                // Register span name to get an ID for native context
                if (span instanceof ReadableSpan) {
                    String spanName = ((ReadableSpan) span).getName();
                    spanNameId = Pyroscope.LabelsWrapper.registerConstant(spanName);
                }
            }
        }

        // 2. Set profiling context in native TLS (calls pthread_setspecific)
        if (asprof != null) {
            asprof.setTracingContext(spanId, spanNameId);
        }

        // 3. Delegate to original storage (sets Java ThreadLocal)
        Scope originalScope = delegate.attach(toAttach);

        // 4. Return wrapped scope that restores profiling context on close
        return new ProfilingScope(originalScope, delegate, asprof);
    }

    @Override
    public Context current() {
        return delegate.current();
    }

    /**
     * Parse 16-character hex span ID to long.
     */
    private static long parseHexSpanId(String hexSpanId) {
        if (hexSpanId == null || hexSpanId.length() != 16) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(hexSpanId, 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Scope wrapper that restores profiling context when closed.
     */
    private static final class ProfilingScope implements Scope {

        private final Scope delegate;
        private final ContextStorage storage;
        private final AsyncProfiler asprof;

        ProfilingScope(Scope delegate, ContextStorage storage, AsyncProfiler asprof) {
            this.delegate = delegate;
            this.storage = storage;
            this.asprof = asprof;
        }

        @Override
        public void close() {
            // 1. Close original scope (restores Java ThreadLocal)
            delegate.close();

            // 2. Restore profiling context to match the now-current OTel context
            Context currentContext = storage.current();
            long spanId = 0L;
            long spanNameId = 0L;

            if (currentContext != null) {
                Span span = Span.fromContext(currentContext);
                SpanContext spanContext = span.getSpanContext();
                if (spanContext.isValid()) {
                    spanId = parseHexSpanId(spanContext.getSpanId());
                    if (span instanceof ReadableSpan) {
                        String spanName = ((ReadableSpan) span).getName();
                        spanNameId = Pyroscope.LabelsWrapper.registerConstant(spanName);
                    }
                }
            }

            if (asprof != null) {
                asprof.setTracingContext(spanId, spanNameId);
            }
        }
    }
}
