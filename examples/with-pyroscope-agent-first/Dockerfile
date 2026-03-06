FROM gradle:8.14.3-jdk17 AS builder
WORKDIR /build
COPY examples/with-otel-extension/build.gradle ./build.gradle
COPY examples/with-otel-extension/settings.gradle ./settings.gradle
RUN gradle dependencies --no-daemon -q
COPY examples/with-otel-extension/src ./src
RUN gradle bootJar --no-daemon -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/build/libs/app.jar app.jar
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.15.0/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
COPY otel-extension/build/libs/pyroscope-otel-javaagent-extension.jar pyroscope-otel-javaagent-extension.jar
COPY pyroscope-agent.jar pyroscope-agent.jar
EXPOSE 8080
CMD ["java", \
     "-javaagent:pyroscope-agent.jar", \
     "-javaagent:opentelemetry-javaagent.jar", \
     "-Dotel.javaagent.extensions=pyroscope-otel-javaagent-extension.jar", \
     "-Dpyroscope.otel.debug=true", \
     "-jar", "app.jar"]
