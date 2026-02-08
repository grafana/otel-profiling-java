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
 * EXPERIMENTAL: This API is experimental and may change or be removed in future versions.
 *
 * Complements PyroscopeOtelSpanProcessor for wall-clock profiling. SpanProcessor handles
 * span start/end on the originating thread, while this ContextStorage wrapper handles
 * context propagation to other threads (e.g., via executors), keeping native TLS in sync.
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
        long spanId = 0L;
        long spanNameId = 0L;

        if (toAttach != null) {
            Span span = Span.fromContext(toAttach);
            SpanContext spanContext = span.getSpanContext();

            if (spanContext.isValid()) {
                spanId = PyroscopeOtelSpanProcessor.parseSpanId(spanContext.getSpanId());

                if (span instanceof ReadableSpan) {
                    String spanName = ((ReadableSpan) span).getName();
                    spanNameId = Pyroscope.LabelsWrapper.registerConstant(spanName);
                }
            }
        }

        if (asprof != null) {
            asprof.setTracingContext(spanId, spanNameId);
        }

        Scope originalScope = delegate.attach(toAttach);
        return new ProfilingScope(originalScope, delegate, asprof);
    }

    @Override
    public Context current() {
        return delegate.current();
    }

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
            delegate.close();

            Context currentContext = storage.current();
            long spanId = 0L;
            long spanNameId = 0L;

            if (currentContext != null) {
                Span span = Span.fromContext(currentContext);
                SpanContext spanContext = span.getSpanContext();
                if (spanContext.isValid()) {
                    spanId = PyroscopeOtelSpanProcessor.parseSpanId(spanContext.getSpanId());

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
