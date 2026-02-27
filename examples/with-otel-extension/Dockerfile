# Build context: repository root (set in docker-compose.yml via `context: ..`)
# This allows copying otel-extension/build/libs/pyroscope-otel.jar built from the repo root.

# Stage 1: Build the example application
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build

# Copy Gradle wrapper from this example directory
COPY examples/with-otel-extension/gradlew ./gradlew
COPY examples/with-otel-extension/gradle ./gradle
RUN chmod +x ./gradlew

# Copy build descriptor files and resolve dependencies first (improves layer caching)
COPY examples/with-otel-extension/build.gradle ./build.gradle
COPY examples/with-otel-extension/settings.gradle ./settings.gradle
RUN ./gradlew dependencies --no-daemon -q

# Copy source and build the fat jar
COPY examples/with-otel-extension/src ./src
RUN ./gradlew bootJar --no-daemon -q


# Stage 2: Runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built application fat jar
COPY --from=builder /build/build/libs/app.jar app.jar

# Download the OpenTelemetry Java agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.15.0/opentelemetry-javaagent.jar opentelemetry-javaagent.jar

# TODO: This is temporary. Once pyroscope-otel is published to Maven Central,
#       replace this COPY with an ADD from the release URL, for example:
#       ADD https://github.com/grafana/otel-profiling-java/releases/download/v1.1.0/pyroscope-otel.jar pyroscope-otel.jar
#
# For now, build the jar first from the repository root:
#   ./gradlew :otel-extension:shadowJar
COPY otel-extension/build/libs/pyroscope-otel.jar pyroscope-otel.jar

EXPOSE 8080

# The OTel Java agent auto-instruments the Spring Boot app (HTTP spans, etc.).
# The pyroscope-otel extension registers PyroscopeOtelSpanProcessor which links
# profiling data to trace spans via the span ID.
#
# Configuration is done entirely via environment variables - see docker-compose.yml:
#   PYROSCOPE_SERVER_ADDRESS, PYROSCOPE_APPLICATION_NAME, PYROSCOPE_FORMAT
#   OTEL_SERVICE_NAME, OTEL_TRACES_EXPORTER
CMD ["java", \
     "-javaagent:opentelemetry-javaagent.jar", \
     "-Dotel.javaagent.extensions=pyroscope-otel.jar", \
     "-jar", "app.jar"]
