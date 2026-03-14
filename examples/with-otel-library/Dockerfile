FROM gradle:8.14.3-jdk17 AS lib-builder
WORKDIR /build
COPY gradle.properties ./gradle.properties
COPY settings.gradle ./settings.gradle
COPY build.gradle ./build.gradle
COPY lib/build.gradle ./lib/build.gradle
RUN gradle :lib:dependencies --no-daemon -q
COPY lib/src/ ./lib/src/
RUN gradle :lib:jar --no-daemon -q

FROM gradle:8.14.3-jdk17 AS builder
WORKDIR /build
COPY examples/with-otel-library/build.gradle ./build.gradle
COPY examples/with-otel-library/settings.gradle ./settings.gradle
COPY gradle.properties ./gradle.properties
COPY --from=lib-builder /build/lib/build/libs/pyroscope-otel.jar ./libs/pyroscope-otel.jar
RUN gradle dependencies --no-daemon -q
COPY examples/with-otel-library/src ./src
RUN gradle bootJar --no-daemon -q

FROM eclipse-temurin:25.0.2_10-jre@sha256:91e59267939a3d14198d537b504af2d836c44894dde19919b2f22192c26841ef
WORKDIR /app
COPY --from=builder /build/build/libs/app.jar app.jar
EXPOSE 8080
CMD ["java", "-Dpyroscope.otel.debug=true", "-jar", "app.jar"]
