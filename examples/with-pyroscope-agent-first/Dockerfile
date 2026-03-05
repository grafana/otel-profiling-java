# Build context: repository root (set via `context: ..` or passed to docker build)
# This allows copying otel-extension/build/libs/pyroscope-otel-javaagent-extension.jar built from the repo root.
#
# UNSUPPORTED CONFIGURATION: This Dockerfile loads pyroscope.jar as a -javaagent
# BEFORE the OTel Java agent. This is used to test and document what happens when
# the agent loading order is reversed.

# Stage 1: Build the example application
# Use the official Gradle image so no Gradle distribution download is needed at build time.
FROM gradle:8.14.3-jdk17 AS builder
WORKDIR /build

# Copy build descriptor files and resolve dependencies first (improves layer caching)
# Reuse the with-otel-extension example app — it's a plain Spring Boot app with no
# compile-time pyroscope/OTel dependencies.
COPY examples/with-otel-extension/build.gradle ./build.gradle
COPY examples/with-otel-extension/settings.gradle ./settings.gradle
RUN gradle dependencies --no-daemon -q

# Copy source and build the fat jar
COPY examples/with-otel-extension/src ./src
RUN gradle bootJar --no-daemon -q


# Stage 2: Runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built application fat jar
COPY --from=builder /build/build/libs/app.jar app.jar

# Download the OpenTelemetry Java agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.15.0/opentelemetry-javaagent.jar opentelemetry-javaagent.jar

# Copy the pyroscope-otel extension jar (built from repo root: ./gradlew :otel-extension:shadowJar)
COPY otel-extension/build/libs/pyroscope-otel-javaagent-extension.jar pyroscope-otel-javaagent-extension.jar

# Copy pyroscope.jar (pre-copied into the repo root before docker build)
# Build from pyroscope-java repo: ./gradlew :agent:shadowJar
# Then copy: cp ../pyroscope-java/agent/build/libs/pyroscope.jar ./pyroscope-agent.jar
COPY pyroscope-agent.jar pyroscope-agent.jar

EXPOSE 8080

# REVERSED ORDER: pyroscope.jar is loaded FIRST, then OTel agent.
# This means:
#   1. PyroscopeAgent.premain() runs first — starts profiling immediately
#   2. OTel agent starts second — loads the pyroscope-otel extension
#   3. The extension injects bootstrap-api into bootstrap CL
#   4. The extension's ByteBuddy hook on PyroscopeAgent.start() never fires
#      (start() was already called in premain)
#   5. tryLoadFromSystemClassLoader() may still set ProfilerApiHolder.INSTANCE
CMD ["java", \
     "-javaagent:pyroscope-agent.jar", \
     "-javaagent:opentelemetry-javaagent.jar", \
     "-Dotel.javaagent.extensions=pyroscope-otel-javaagent-extension.jar", \
     "-Dpyroscope.otel.debug=true", \
     "-jar", "app.jar"]
