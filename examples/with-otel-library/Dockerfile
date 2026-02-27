# Build context: repository root (set in docker-compose.yml via `context: ..`)
# This allows copying build/libs/pyroscope-otel.jar built from the repo root.

# Stage 1: Build the example application
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /build

# Copy Gradle wrapper from this example directory
COPY examples/with-otel-library/gradlew ./gradlew
COPY examples/with-otel-library/gradle ./gradle
RUN chmod +x ./gradlew

# Copy build descriptor files and resolve dependencies first (improves layer caching)
COPY examples/with-otel-library/build.gradle ./build.gradle
COPY examples/with-otel-library/settings.gradle ./settings.gradle

# TODO: This is temporary. Once pyroscope-otel is published to Maven Central,
#       remove this COPY and replace the fileTree dependency in build.gradle with:
#       implementation 'io.pyroscope:otel:1.0.4'
#
# For now, build the jar first from the repository root:
#   ./gradlew shadowJar
COPY build/libs/pyroscope-otel.jar ./libs/pyroscope-otel.jar

RUN ./gradlew dependencies --no-daemon -q

# Copy source and build the fat jar
COPY examples/with-otel-library/src ./src
RUN ./gradlew bootJar --no-daemon -q


# Stage 2: Runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built application fat jar (contains Pyroscope agent and OTel SDK as embedded deps)
COPY --from=builder /build/build/libs/app.jar app.jar

EXPOSE 8080

# No -javaagent flags - Pyroscope and OTel are used as libraries bundled in app.jar.
# Configuration is done via environment variables - see docker-compose.yml:
#   PYROSCOPE_SERVER_ADDRESS, PYROSCOPE_APPLICATION_NAME
CMD ["java", "-jar", "app.jar"]
