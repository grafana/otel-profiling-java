# Build context: repository root (set in docker-compose.yml via `context: ..`)
# Multi-stage build:
#   Stage 0: Builds pyroscope-java dependencies from source
#   Stage 1: Builds otel-extension from source
#   Stage 2: Builds the example application
#   Stage 3: Creates runtime image with both artifacts

# Stage 0: Build pyroscope-java agent and bootstrap-api from source
# TODO: Replace branch vk/5c8d-merge-squash-the with main once PR is merged
FROM gradle:8.14.3-jdk17 AS pyroscope-builder
WORKDIR /pyroscope-build

# Clone pyroscope-java repository
RUN git clone --depth 1 --branch vk/5c8d-merge-squash-the \
    https://github.com/korniltsev-grafanista-yolo-vibecoder239/pyroscope-java.git .

# Build and publish agent and bootstrap-api to local Maven repository
RUN ./gradlew :agent:publishToMavenLocal :bootstrap-api:publishToMavenLocal --no-daemon -q


# Stage 1: Build the otel-extension from source
FROM gradle:8.14.3-jdk17 AS extension-builder
WORKDIR /build

# Copy Maven repository from pyroscope-builder stage
COPY --from=pyroscope-builder /root/.m2/repository /root/.m2/repository

# Copy Gradle wrapper (changes rarely - good for caching)
COPY gradle/ ./gradle/
COPY gradlew ./gradlew
COPY gradlew.bat ./gradlew.bat

# Copy root project configuration (defines versions, group)
COPY gradle.properties ./gradle.properties
COPY settings.gradle ./settings.gradle
COPY build.gradle ./build.gradle

# Copy otel-extension module configuration
COPY otel-extension/build.gradle ./otel-extension/build.gradle

# Resolve dependencies first using mavenLocal (cached until build.gradle changes)
RUN ./gradlew :otel-extension:dependencies --no-daemon -q -PuseMavenLocal

# Copy source code (changes frequently)
COPY otel-extension/src/ ./otel-extension/src/

# Build the shadow jar with all dependencies included
RUN ./gradlew :otel-extension:shadowJar --no-daemon -q -PuseMavenLocal


# Stage 2: Build the example application
# Use the official Gradle image so no Gradle distribution download is needed at build time.
FROM gradle:8.14.3-jdk17 AS builder
WORKDIR /build

# Copy build descriptor files and resolve dependencies first (improves layer caching)
COPY examples/with-otel-extension-manual-start/build.gradle ./build.gradle
COPY examples/with-otel-extension-manual-start/settings.gradle ./settings.gradle
RUN gradle dependencies --no-daemon -q

# Copy source and build the fat jar
COPY examples/with-otel-extension-manual-start/src ./src
RUN gradle bootJar --no-daemon -q


# Stage 3: Runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built application fat jar
COPY --from=builder /build/build/libs/app.jar app.jar

# Download the OpenTelemetry Java agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.15.0/opentelemetry-javaagent.jar opentelemetry-javaagent.jar

# Copy the built otel-extension from the builder stage
COPY --from=extension-builder /build/otel-extension/build/libs/pyroscope-otel-javaagent-extension.jar pyroscope-otel-javaagent-extension.jar

EXPOSE 8080

# The OTel Java agent auto-instruments the Spring Boot app (HTTP spans, etc.).
# The pyroscope-otel extension registers PyroscopeOtelSpanProcessor which links
# profiling data to trace spans via the span ID.
#
# OTEL_PYROSCOPE_START_PROFILING=false tells the extension NOT to start the
# Pyroscope profiler automatically - the app starts it programmatically instead
# (see App.java). This allows full control over profiling configuration.
#
# The extension still discovers the running profiler via the system ClassLoader
# (OtelProfilerSdkBridge) and wires PyroscopeOtelSpanProcessor for trace-profile
# correlation.
#
# Configuration is done entirely via environment variables - see docker-compose.yml.
CMD ["java", \
     "-javaagent:opentelemetry-javaagent.jar", \
     "-Dotel.javaagent.extensions=pyroscope-otel-javaagent-extension.jar", \
     "-Dpyroscope.otel.debug=true", \
     "-jar", "app.jar"]
