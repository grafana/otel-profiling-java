package io.otel.pyroscope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.pyroscope.labels.LabelsSet;
import io.pyroscope.labels.ScopedContext;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class PyroscopeOtelSpanProcessor implements SpanProcessor {

    private static final String LABEL_PROFILE_ID = "profile_id";
    private static final String LABEL_SPAN_NAME = "span_name";

    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_ID = AttributeKey.stringKey("pyroscope.profile.id");
    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_URL = AttributeKey.stringKey("pyroscope.profile.url");
    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_BASELINE_URL = AttributeKey.stringKey("pyroscope.profile.baseline.url");
    private static final AttributeKey<String> ATTRIBUTE_KEY_PROFILE_DIFF_URL = AttributeKey.stringKey("pyroscope.profile.diff.url");

    private final Map<String, PyroscopeContextHolder> pyroscopeContexts = new ConcurrentHashMap<>();

    private final PyroscopeOtelConfiguration configuration;

    public PyroscopeOtelSpanProcessor(PyroscopeOtelConfiguration configuration) {
        this.configuration = configuration;
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

        ScopedContext pyroscopeContext = new ScopedContext(new LabelsSet(labels));
        span.setAttribute(ATTRIBUTE_KEY_PROFILE_ID, profileId);
        long now = now();
        PyroscopeContextHolder pyroscopeContextHolder = new PyroscopeContextHolder(profileId, pyroscopeContext, now);
        pyroscopeContexts.put(profileId, pyroscopeContextHolder);
        if (configuration.optimisticTimestamps) {
            long optimisticEnd = now + TimeUnit.HOURS.toMillis(1);
            if (configuration.addProfileURL) {
                span.setAttribute(ATTRIBUTE_KEY_PROFILE_URL, buildProfileUrl(pyroscopeContextHolder, optimisticEnd));
            }
            if (configuration.addProfileBaselineURLs) {
                String q = buildComparisonQuery(pyroscopeContextHolder, optimisticEnd);
                span.setAttribute(ATTRIBUTE_KEY_PROFILE_BASELINE_URL, configuration.pyroscopeEndpoint + "/comparison?" + q);
                span.setAttribute(ATTRIBUTE_KEY_PROFILE_DIFF_URL, configuration.pyroscopeEndpoint + "/comparison-diff?" + q);
            }
        }
    }

    @Override
    public void onEnd(ReadableSpan span) {
        String profileId = span.getAttribute(ATTRIBUTE_KEY_PROFILE_ID);
        if (profileId == null) {
            return;
        }
        PyroscopeContextHolder pyroscopeContext = pyroscopeContexts.remove(profileId);
        if (pyroscopeContext == null) {
            return;
        }

        try {
            if (configuration.optimisticTimestamps) {
                return;
            }
            if (sdkSpanInternals == null) {
                return;
            }
            if (!(span instanceof ReadWriteSpan)) {
                return;
            }
            long now = now();
            if (configuration.addProfileURL) {
                sdkSpanInternals.setAttribute(span, ATTRIBUTE_KEY_PROFILE_URL, buildProfileUrl(pyroscopeContext, now));
            }
            if (configuration.addProfileBaselineURLs) {
                String q = buildComparisonQuery(pyroscopeContext, now);
                sdkSpanInternals.setAttribute(span, ATTRIBUTE_KEY_PROFILE_BASELINE_URL, configuration.pyroscopeEndpoint + "/comparison?" + q);
                sdkSpanInternals.setAttribute(span, ATTRIBUTE_KEY_PROFILE_DIFF_URL, configuration.pyroscopeEndpoint + "/comparison-diff?" + q);
            }
        } finally {
            pyroscopeContext.ctx.close();
        }

    }


    private String buildComparisonQuery(PyroscopeContextHolder pyroscopeContext, long untilMilis) {
        StringBuilder qb = new StringBuilder();
        pyroscopeContext.ctx.forEach((k, v) -> {
            if (k.equals(LABEL_PROFILE_ID)) {
                return;
            }
            if (configuration.profileBaselineLabels.containsKey(k)) {
                return;
            }
            writeLabel(qb, k, v);
        });
        for (Map.Entry<String, String> it : configuration.profileBaselineLabels.entrySet()) {
            writeLabel(qb, it.getKey(), it.getValue());
        }
        StringBuilder query = new StringBuilder();
        String from = Long.toString(pyroscopeContext.startTimeMillis - 3600000);
        String until = Long.toString(untilMilis);
        String baseLineQuery = String.format("%s{%s}", configuration.appName, qb.toString());
        query.append("query=").append(urlEncode(baseLineQuery));
        query.append("&from=").append(from);
        query.append("&until=").append(until);

        query.append("&leftQuery=").append(urlEncode(baseLineQuery));
        query.append("&leftFrom=").append(from);
        query.append("&leftUntil=").append(until);

        query.append("&rightQuery=").append(urlEncode(String.format("%s{%s=\"%s\"}", configuration.appName, LABEL_PROFILE_ID, pyroscopeContext.profileId)));
        query.append("&rightFrom=").append(from);
        query.append("&rightUntil=").append(until);
        return query.toString();
    }

    private void writeLabel(StringBuilder sb, String k, String v) {
        if (sb.length() != 0) {
            sb.append(",");
        }
        sb.append(k).append("=\"").append(v).append("\"");
    }

    private String buildProfileUrl(PyroscopeContextHolder pyroscopeContext, long untilMillis) {
        String query = String.format("%s{%s=\"%s\"}", configuration.appName, LABEL_PROFILE_ID, pyroscopeContext.profileId);
        return String.format("%s?query=%s&from=%d&until=%d", configuration.pyroscopeEndpoint,
                urlEncode(query),
                pyroscopeContext.startTimeMillis,
                untilMillis
        );
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String urlEncode(String query) {
        try {
            return URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static class PyroscopeContextHolder {
        final String profileId;
        final ScopedContext ctx;
        final long startTimeMillis;

        PyroscopeContextHolder(String profileId, ScopedContext ctx, long startTimeMillis) {
            this.profileId = profileId;
            this.ctx = ctx;
            this.startTimeMillis = startTimeMillis;
        }
    }

    public static boolean isRootSpan(ReadableSpan span) {
        SpanContext parent = span.getParentSpanContext();
        boolean noParent = parent == SpanContext.getInvalid();
        boolean remote = parent.isRemote();
        return remote || noParent;
    }

    private static final SdkSpanInternals sdkSpanInternals;

    static {
        SdkSpanInternals internals;
        try {
            internals = new SdkSpanInternals();
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            internals = null;
        }
        sdkSpanInternals = internals;
    }

    private static class SdkSpanInternals {
        final Field lock; // Object
        final Field attributes; // AttributesMap
        final AtomicBoolean error = new AtomicBoolean(false);

        public SdkSpanInternals() throws ClassNotFoundException, NoSuchFieldException {
            Class<?> cls = Class.forName("io.opentelemetry.sdk.trace.SdkSpan");
            lock = cls.getDeclaredField("lock");
            attributes = cls.getDeclaredField("attributes");
            lock.setAccessible(true);
            attributes.setAccessible(true);
        }

        <T> void setAttribute(ReadableSpan span, AttributeKey<T> key, T value) {
            if (error.get()) {
                return;
            }
            final Object lock;
            try {
                lock = this.lock.get(span);
            } catch (IllegalAccessException e) {
                if (error.compareAndSet(false, true)) {
                    e.printStackTrace();
                }
                return;
            }
            final HashMap attributes;
            try {
                attributes = (HashMap) this.attributes.get(span);
            } catch (IllegalAccessException | ClassCastException e) {
                if (error.compareAndSet(false, true)) {
                    e.printStackTrace();
                }
                return;
            }
            synchronized (lock) {
                attributes.put(key, value);
            }
        }

    }
}
