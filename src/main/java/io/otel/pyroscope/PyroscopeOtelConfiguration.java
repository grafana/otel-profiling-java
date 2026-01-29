package io.otel.pyroscope;


public class PyroscopeOtelConfiguration {
    //todo think about removing both options, so that users don't need to configure or think about anything
    final boolean rootSpanOnly;
    final boolean addSpanName;
    final boolean contextPropagationEnabled;

    private PyroscopeOtelConfiguration(Builder builder) {
        this.rootSpanOnly = builder.rootSpanOnly;
        this.addSpanName = builder.addSpanName;
        this.contextPropagationEnabled = builder.contextPropagationEnabled;
    }

    @Override
    public String toString() {
        return "PyroscopeOtelConfiguration{" +
                "rootSpanOnly=" + rootSpanOnly +
                ", addSpanName=" + addSpanName +
                ", contextPropagationEnabled=" + contextPropagationEnabled +
                '}';
    }

    public static class Builder {
        boolean rootSpanOnly = true;
        boolean addSpanName = true;
        boolean contextPropagationEnabled = true;

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

        public Builder setContextPropagationEnabled(boolean contextPropagationEnabled) {
            this.contextPropagationEnabled = contextPropagationEnabled;
            return this;
        }

        public PyroscopeOtelConfiguration build() {
            return new PyroscopeOtelConfiguration(this);
        }
    }
}
