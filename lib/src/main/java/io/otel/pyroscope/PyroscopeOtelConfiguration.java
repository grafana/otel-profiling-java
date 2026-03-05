package io.otel.pyroscope;


public class PyroscopeOtelConfiguration {
    final boolean rootSpanOnly;
    final boolean addSpanName;

    private PyroscopeOtelConfiguration(Builder builder) {
        this.rootSpanOnly = builder.rootSpanOnly;
        this.addSpanName = builder.addSpanName;
    }

    @Override
    public String toString() {
        return "PyroscopeOtelConfiguration{" +
                ", rootSpanOnly=" + rootSpanOnly +
                ", addSpanName=" + addSpanName +
                '}';
    }

    public static class Builder {
        boolean rootSpanOnly = true;
        boolean addSpanName = true;

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

        public PyroscopeOtelConfiguration build() {
            return new PyroscopeOtelConfiguration(this);
        }
    }
}
