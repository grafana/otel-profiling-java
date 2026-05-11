FROM gradle:8.14.4-jdk17@sha256:634d4b7fdb3c635091c122d94989066c17d8ec43ead10400638f47529d44f395 AS lib-builder
WORKDIR /build
COPY gradle.properties ./gradle.properties
COPY settings.gradle ./settings.gradle
COPY build.gradle ./build.gradle
COPY lib/build.gradle ./lib/build.gradle
RUN gradle :lib:dependencies --no-daemon -q
COPY lib/src/ ./lib/src/
RUN gradle :lib:jar --no-daemon -q

FROM gradle:8.14.4-jdk17@sha256:634d4b7fdb3c635091c122d94989066c17d8ec43ead10400638f47529d44f395 AS builder
WORKDIR /build
COPY examples/with-otel-library/build.gradle ./build.gradle
COPY examples/with-otel-library/settings.gradle ./settings.gradle
COPY gradle.properties ./gradle.properties
COPY --from=lib-builder /build/lib/build/libs/pyroscope-otel.jar ./libs/pyroscope-otel.jar
RUN gradle dependencies --no-daemon -q
COPY examples/with-otel-library/src ./src
RUN gradle bootJar --no-daemon -q

FROM eclipse-temurin:17-jre@sha256:ff10c54cd455c944bd10528393462c02e487dfdef00b779777d59c6cf31d91cf
WORKDIR /app
COPY --from=builder /build/build/libs/app.jar app.jar
EXPOSE 8080
CMD ["java", "-Dpyroscope.otel.debug=true", "-jar", "app.jar"]
