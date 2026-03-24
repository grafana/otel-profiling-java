FROM gradle:8.14.3-jdk17@sha256:ce50078d456e497bc86b76d386503e84aa45416d85069ad66bc072bf07566b25 AS extension-builder
WORKDIR /build
COPY gradle.properties ./gradle.properties
COPY settings.gradle ./settings.gradle
COPY build.gradle ./build.gradle
COPY otel-extension/build.gradle ./otel-extension/build.gradle
RUN gradle :otel-extension:dependencies --no-daemon -q
COPY otel-extension/src/ ./otel-extension/src/
RUN gradle :otel-extension:shadowJar --no-daemon -q

FROM gradle:8.14.3-jdk17@sha256:ce50078d456e497bc86b76d386503e84aa45416d85069ad66bc072bf07566b25 AS builder
WORKDIR /build
COPY examples/with-otel-extension/build.gradle ./build.gradle
COPY examples/with-otel-extension/settings.gradle ./settings.gradle
RUN gradle dependencies --no-daemon -q
COPY examples/with-otel-extension/src ./src
RUN gradle bootJar --no-daemon -q

FROM eclipse-temurin:17-jre@sha256:ff10c54cd455c944bd10528393462c02e487dfdef00b779777d59c6cf31d91cf
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
