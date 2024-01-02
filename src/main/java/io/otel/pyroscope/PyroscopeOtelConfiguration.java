package io.otel.pyroscope;

import java.util.Collections;
import java.util.Map;

public class PyroscopeOtelConfiguration {
    final String appName;
    final String pyroscopeEndpoint;
    final boolean rootSpanOnly;
    final boolean addSpanName;
    final boolean addProfileURL;
    final boolean addProfileBaselineURLs;
    final boolean optimisticTimestamps;
    final Map<String, String> profileBaselineLabels;

    private PyroscopeOtelConfiguration(Builder builder) {
        this.appName = builder.appName;
        this.pyroscopeEndpoint = builder.pyroscopeEndpoint;
        this.rootSpanOnly = builder.rootSpanOnly;
        this.addSpanName = builder.addSpanName;
        this.addProfileURL = builder.addProfileURL;
        this.addProfileBaselineURLs = builder.addProfileBaselineURLs;
        this.profileBaselineLabels = builder.profileBaselineLabels;
        this.optimisticTimestamps = builder.optimisticTimestamps;
    }

    @Override
    public String toString() {
        return "PyroscopeOtelConfiguration{" +
                "appName='" + appName + '\'' +
                ", rootSpanOnly=" + rootSpanOnly +
                ", addSpanName=" + addSpanName +
                '}';
    }

    public static class Builder {
        String appName;
        String pyroscopeEndpoint;
        boolean rootSpanOnly = true;
        boolean addSpanName = true;
        boolean addProfileURL = false;
        boolean addProfileBaselineURLs = false;
        boolean optimisticTimestamps = false;
        Map<String, String> profileBaselineLabels = Collections.emptyMap();

        public Builder() {
        }

        public Builder setAppName(String appName) {
            this.appName = appName;
            return this;
        }

        public Builder setPyroscopeEndpoint(String pyroscopeEndpoint) {
            this.pyroscopeEndpoint = pyroscopeEndpoint;
            return this;
        }

        public Builder setProfileBaselineLabels(Map<String, String> profileBaselineLabels) {
            this.profileBaselineLabels = profileBaselineLabels;
            return this;
        }

        public Builder setRootSpanOnly(boolean rootSpanOnly) {
            this.rootSpanOnly = rootSpanOnly;
            return this;
        }

        public Builder setAddSpanName(boolean addSpanName) {
            this.addSpanName = addSpanName;
            return this;
        }

        public Builder setAddProfileURL(boolean addProfileURL) {
            this.addProfileURL = addProfileURL;
            return this;
        }

        public Builder setAddProfileBaselineURLs(boolean addProfileBaselineURLs) {
            this.addProfileBaselineURLs = addProfileBaselineURLs;
            return this;
        }

        public Builder setOptimisticTimestamps(boolean optimisticTimestamps) {
            this.optimisticTimestamps = optimisticTimestamps;
            return this;
        }

        public PyroscopeOtelConfiguration build() {
            return new PyroscopeOtelConfiguration(this);
        }
    }
}
