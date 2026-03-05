package io.otel.pyroscope;


public class PyroscopeOtelConfiguration {
    /** @deprecated This field is no longer used. */
    @Deprecated
    final boolean rootSpanOnly;
    /** @deprecated This field is no longer used. */
    @Deprecated
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

        /** @deprecated This setting is no longer used. */
        @Deprecated
        public Builder setRootSpanOnly(boolean rootSpanOnly) {
            this.rootSpanOnly = rootSpanOnly;
            return this;
        }

        /** @deprecated This setting is no longer used. */
        @Deprecated
        public Builder setAddSpanName(boolean addSpanName) {
            this.addSpanName = addSpanName;
            return this;
        }

        public PyroscopeOtelConfiguration build() {
            return new PyroscopeOtelConfiguration(this);
        }
    }
}
