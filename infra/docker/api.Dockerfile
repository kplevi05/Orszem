# Őrszem Demo v1 — API image
# Multi-stage build: Gradle build on JDK 21, slim JRE runtime.

FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY services/api/settings.gradle.kts services/api/build.gradle.kts services/api/gradle.properties ./
COPY services/api/gradle ./gradle
RUN gradle --no-daemon dependencies --refresh-dependencies || true
COPY services/api/src ./src
RUN gradle --no-daemon clean bootJar -x test

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --system orszem && useradd --system --gid orszem orszem
WORKDIR /app
COPY --from=build /workspace/build/libs/orszem-api.jar /app/orszem-api.jar
USER orszem
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/orszem-api.jar"]
