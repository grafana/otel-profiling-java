FROM gradle:8.14.4-jdk17@sha256:1ad0ceec1f44ceeeafbdb67fbe68e974e3ebbcb73b2a8622d64e869172a03c81 AS extension-builder
WORKDIR /build
COPY gradle.properties ./gradle.properties
COPY settings.gradle ./settings.gradle
COPY build.gradle ./build.gradle
COPY otel-extension/build.gradle ./otel-extension/build.gradle
RUN gradle :otel-extension:dependencies --no-daemon -q
COPY otel-extension/src/ ./otel-extension/src/
RUN gradle :otel-extension:shadowJar --no-daemon -q
RUN PYROSCOPE_VERSION=$(grep pyroscope_version gradle.properties | cut -d= -f2) && \
    wget -q -O pyroscope-agent.jar "https://repo1.maven.org/maven2/io/pyroscope/agent/${PYROSCOPE_VERSION}/agent-${PYROSCOPE_VERSION}.jar"

FROM gradle:8.14.4-jdk17@sha256:1ad0ceec1f44ceeeafbdb67fbe68e974e3ebbcb73b2a8622d64e869172a03c81 AS builder
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
COPY --from=extension-builder /build/otel-extension/build/libs/pyroscope-otel-javaagent-extension.jar pyroscope-otel-javaagent-extension.jar
COPY --from=extension-builder /build/pyroscope-agent.jar pyroscope-agent.jar
EXPOSE 8080
CMD ["java", \
     "-javaagent:pyroscope-agent.jar", \
     "-javaagent:opentelemetry-javaagent.jar", \
     "-Dotel.javaagent.extensions=pyroscope-otel-javaagent-extension.jar", \
     "-Dpyroscope.otel.debug=true", \
     "-jar", "app.jar"]
