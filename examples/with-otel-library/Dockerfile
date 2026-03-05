# Build context: repository root (set in docker-compose.yml via `context: ..`)
# This allows copying lib/build/libs/pyroscope-otel.jar built from the repo root.

# Stage 1: Build the example application
# Use the official Gradle image so no Gradle distribution download is needed at build time.
FROM gradle:8.14.3-jdk17 AS builder
WORKDIR /build

# Copy build descriptor files and resolve dependencies first (improves layer caching)
COPY examples/with-otel-library/build.gradle ./build.gradle
COPY examples/with-otel-library/settings.gradle ./settings.gradle
# Root gradle.properties provides pyroscope_version used in build.gradle
COPY gradle.properties ./gradle.properties

# TODO: This is temporary. Once pyroscope-otel is published to Maven Central,
#       remove this COPY and replace the fileTree dependency in build.gradle with:
#       implementation 'io.pyroscope:otel:1.1.0'
#
# We use the thin jar (pyroscope-otel.jar) here - it contains only the
# io.otel.pyroscope.* integration classes with no vendored dependencies.
# io.pyroscope:agent is declared separately in build.gradle so there is only
# one copy of the pyroscope classes on the classpath.
#
# Build the thin jar from the repository root:
#   ./gradlew :lib:jar
COPY lib/build/libs/pyroscope-otel.jar ./libs/pyroscope-otel.jar

RUN gradle dependencies --no-daemon -q

# Copy source and build the fat jar
COPY examples/with-otel-library/src ./src
RUN gradle bootJar --no-daemon -q


# Stage 2: Runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built application fat jar (contains Pyroscope agent and OTel SDK as embedded deps)
COPY --from=builder /build/build/libs/app.jar app.jar

EXPOSE 8080

# No -javaagent flags - Pyroscope and OTel are used as libraries bundled in app.jar.
# Configuration is done via environment variables - see docker-compose.yml:
#   PYROSCOPE_SERVER_ADDRESS, PYROSCOPE_APPLICATION_NAME
CMD ["java", "-Dpyroscope.otel.debug=true", "-jar", "app.jar"]
