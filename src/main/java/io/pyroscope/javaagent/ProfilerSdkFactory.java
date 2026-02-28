package io.pyroscope.javaagent;

import io.pyroscope.agent.api.ProfilerApi;

public class ProfilerSdkFactory {
    public static ProfilerApi create() {
        return new ProfilerSdk();
    }
}
