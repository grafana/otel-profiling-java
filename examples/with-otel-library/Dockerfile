FROM gradle:8.14.3-jdk17@sha256:ce50078d456e497bc86b76d386503e84aa45416d85069ad66bc072bf07566b25 AS lib-builder
WORKDIR /build
COPY gradle.properties ./gradle.properties
COPY settings.gradle ./settings.gradle
COPY build.gradle ./build.gradle
COPY lib/build.gradle ./lib/build.gradle
RUN gradle :lib:dependencies --no-daemon -q
COPY lib/src/ ./lib/src/
RUN gradle :lib:jar --no-daemon -q

FROM gradle:8.14.3-jdk17@sha256:ce50078d456e497bc86b76d386503e84aa45416d85069ad66bc072bf07566b25 AS builder
WORKDIR /build
COPY examples/with-otel-library/build.gradle ./build.gradle
COPY examples/with-otel-library/settings.gradle ./settings.gradle
COPY gradle.properties ./gradle.properties
COPY --from=lib-builder /build/lib/build/libs/pyroscope-otel.jar ./libs/pyroscope-otel.jar
RUN gradle dependencies --no-daemon -q
COPY examples/with-otel-library/src ./src
RUN gradle bootJar --no-daemon -q

FROM eclipse-temurin:17.0.18_8-jre@sha256:07c1f0f0c3f3c714cea7a9250a8418ec329baa2cea1f77dcda2a7332ff937633
WORKDIR /app
COPY --from=builder /build/build/libs/app.jar app.jar
EXPOSE 8080
CMD ["java", "-Dpyroscope.otel.debug=true", "-jar", "app.jar"]
