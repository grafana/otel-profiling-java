package io.otel.pyroscope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import io.pyroscope.javaagent.api.ProfilerScopedContext;
import io.pyroscope.javaagent.impl.ProfilerScopedContextWrapper;
import io.pyroscope.labels.v2.LabelsSet;
import io.pyroscope.labels.v2.ScopedContext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final String LABEL_PROFILE_ID = "profile_id";
    private static final String LABEL_SPAN_NAME = "span_name";

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");

    private final Map<String, ProfilerScopedContext> pyroscopeContexts = new ConcurrentHashMap<>();

    private final PyroscopeOtelConfiguration configuration;
    private final OtelProfilerSdkBridge profilerSdk;

    public PyroscopeOtelSpanProcessor(PyroscopeOtelConfiguration configuration, OtelProfilerSdkBridge profilerSdk) {
        this.configuration = configuration;
        this.profilerSdk = profilerSdk;
    }

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
        if (configuration.rootSpanOnly && !isRootSpan(span)) {
            return;
        }
        Map<String, String> labels = new HashMap<>();
        String profileId = span.getSpanContext().getSpanId();
        labels.put(LABEL_PROFILE_ID, profileId);
        if (configuration.addSpanName) {
            labels.put(LABEL_SPAN_NAME, span.getName());
        }

        ProfilerScopedContext scopedContext = createScopedContext(labels);
        span.setAttribute(ATTRIBUTE_KEY_PROFILE_ID, profileId);
        pyroscopeContexts.put(profileId, scopedContext);
    }


    @Override
    public void onEnd(ReadableSpan span) {
        String profileId = span.getAttribute(ATTRIBUTE_KEY_PROFILE_ID);
        if (profileId == null) {
            return;
        }
        ProfilerScopedContext pyroscopeContext = pyroscopeContexts.remove(profileId);
        if (pyroscopeContext == null) {
            return;
        }

        pyroscopeContext.close();
    }

    private ProfilerScopedContext createScopedContext(Map<String, String> labels) {
        if (profilerSdk == null) {
            return new ProfilerScopedContextWrapper(new ScopedContext(new LabelsSet(labels)));
        }
        return profilerSdk.createScopedContext(labels);
    }

    public static boolean isRootSpan(ReadableSpan span) {
        SpanContext parent = span.getParentSpanContext();
        boolean noParent = parent == SpanContext.getInvalid();
        boolean remote = parent.isRemote();
        return remote || noParent;
    }
}
