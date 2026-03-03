package io.pyroscope.javaagent;

import io.pyroscope.agent.api.IProfilingBridge;

public class ProfilingBridgeFactory {
    public static IProfilingBridge create() {
        return new ProfilingBridgeImpl();
    }
}
