package io.otel.pyroscope;


public class PyroscopeOtelConfiguration {
    //todo think about removing both options, so that users don't need to configure or think about anything
    final boolean rootSpanOnly;
    final boolean addSpanName;
    final boolean appSdkEnabled;

    private PyroscopeOtelConfiguration(Builder builder) {
        this.rootSpanOnly = builder.rootSpanOnly;
        this.addSpanName = builder.addSpanName;
        this.appSdkEnabled = builder.appSdkEnabled;
    }

    @Override
    public String toString() {
        return "PyroscopeOtelConfiguration{" +
                "rootSpanOnly=" + rootSpanOnly +
                ", addSpanName=" + addSpanName +
                ", appSdkEnabled=" + appSdkEnabled +
                '}';
    }

    public static class Builder {
        boolean rootSpanOnly = true;
        boolean addSpanName = true;
        boolean appSdkEnabled = false;

        public Builder() {
        }

        public Builder setRootSpanOnly(boolean rootSpanOnly) {
            this.rootSpanOnly = rootSpanOnly;
            return this;
        }

        public Builder setAddSpanName(boolean addSpanName) {
            this.addSpanName = addSpanName;
            return this;
        }

        public Builder setAppSdkEnabled(boolean appSdkEnabled) {
            this.appSdkEnabled = appSdkEnabled;
            return this;
        }

        public PyroscopeOtelConfiguration build() {
            return new PyroscopeOtelConfiguration(this);
        }
    }
}
