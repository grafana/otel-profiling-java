FROM gradle:8.14.3-jdk17 AS extension-builder
WORKDIR /build
COPY gradle.properties ./gradle.properties
COPY settings.gradle ./settings.gradle
COPY build.gradle ./build.gradle
COPY otel-extension/build.gradle ./otel-extension/build.gradle
RUN gradle :otel-extension:dependencies --no-daemon -q
COPY otel-extension/src/ ./otel-extension/src/
RUN gradle :otel-extension:shadowJar --no-daemon -q

FROM gradle:8.14.3-jdk17 AS builder
WORKDIR /build
COPY examples/with-otel-extension-manual-start/build.gradle ./build.gradle
COPY examples/with-otel-extension-manual-start/settings.gradle ./settings.gradle
COPY gradle.properties ./gradle.properties
RUN gradle dependencies --no-daemon -q
COPY examples/with-otel-extension-manual-start/src ./src
RUN gradle bootJar --no-daemon -q

FROM eclipse-temurin:17.0.18_8-jre@sha256:7a7b6ad2ab918a29b9b2b87b34158a4b5ad20d23dee33687306359eca2d8063e
WORKDIR /app
COPY --from=builder /build/build/libs/app.jar app.jar
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.15.0/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
COPY --from=extension-builder /build/otel-extension/build/libs/pyroscope-otel-javaagent-extension.jar pyroscope-otel-javaagent-extension.jar
EXPOSE 8080
CMD ["java", \
     "-javaagent:opentelemetry-javaagent.jar", \
     "-Dotel.javaagent.extensions=pyroscope-otel-javaagent-extension.jar", \
     "-Dpyroscope.otel.debug=true", \
     "-jar", "app.jar"]
