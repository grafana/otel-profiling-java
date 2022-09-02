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
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;



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
        pyroscopeContexts.put(profileId, new PyroscopeContextHolder(profileId, pyroscopeContext, now()));
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (!(span instanceof ReadWriteSpan)) {
            return;
        }
        String profileId = span.getAttribute(ATTRIBUTE_KEY_PROFILE_ID);
        if (profileId == null ) {
            return;
        }
        PyroscopeContextHolder pyroscopeContext = pyroscopeContexts.remove(profileId);
        if (pyroscopeContext == null) {
            return;
        }
        if (configuration.addProfileURL) {
            ((ReadWriteSpan) span).setAttribute(ATTRIBUTE_KEY_PROFILE_URL, buildProfileUrl(pyroscopeContext));
        }
        if (configuration.addProfileBaselineURLs) {
            addBaselineURLs((ReadWriteSpan) span, pyroscopeContext);
        }
        pyroscopeContext.ctx.close();
    }


    private void addBaselineURLs(ReadWriteSpan span, PyroscopeContextHolder pyroscopeContext) {
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
        String until = Long.toString(now());
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

        String strQuery = query.toString();
        span.setAttribute(ATTRIBUTE_KEY_PROFILE_BASELINE_URL, configuration.pyroscopeEndpoint + "/comparison?" + strQuery);
        span.setAttribute(ATTRIBUTE_KEY_PROFILE_DIFF_URL, configuration.pyroscopeEndpoint + "/comparison-diff?" + strQuery);
    }

    private void writeLabel(StringBuilder sb, String k, String v) {
        if (sb.length() != 0) {
            sb.append(",");
        }
        sb.append(k).append("=\"").append(v).append("\"");
    }

    private String buildProfileUrl(PyroscopeContextHolder pyroscopeContext) {
        String query = String.format("%s{%s=\"%s\"}", configuration.appName, LABEL_PROFILE_ID, pyroscopeContext.profileId);
        return String.format("%s?query=%s&from=%d&until=%d", configuration.pyroscopeEndpoint,
                urlEncode(query),
                pyroscopeContext.startTimeMillis,
                now()
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
}
