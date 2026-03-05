package io.otel.pyroscope;


/**
 * @deprecated Configuration is no longer needed. Use {@link io.otel.pyroscope.PyroscopeOtelSpanProcessor#PyroscopeOtelSpanProcessor()} instead.
 */
@Deprecated
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

    /**
     * @deprecated Use {@link io.otel.pyroscope.PyroscopeOtelSpanProcessor#PyroscopeOtelSpanProcessor()} instead.
     */
    @Deprecated
    public static class Builder {
        boolean rootSpanOnly = true;
        boolean addSpanName = true;

        /** @deprecated */
        @Deprecated
        public Builder() {
        }

        /** @deprecated */
        @Deprecated
        public Builder setRootSpanOnly(boolean rootSpanOnly) {
            this.rootSpanOnly = rootSpanOnly;
            return this;
        }

        /** @deprecated */
        @Deprecated
        public Builder setAddSpanName(boolean addSpanName) {
            this.addSpanName = addSpanName;
            return this;
        }

        /** @deprecated */
        @Deprecated
        public PyroscopeOtelConfiguration build() {
            return new PyroscopeOtelConfiguration(this);
        }
    }
}
