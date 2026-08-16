# syntax=docker/dockerfile:1
#
# Production image of the AI Dev Platform.
#
# The platform talks to the Docker daemon to create sandboxes, so the socket must be mounted at
# runtime. It never runs a build itself: everything untrusted happens inside the sandbox containers.

# ----------------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies first: this layer is cached as long as the pom does not change.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# --------------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-jammy

# docker-cli is not required (the platform uses the Docker HTTP API), curl is used by the healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Runs as a non-root user. Add this uid to the host "docker" group (or use a socket proxy) so it can
# reach /var/run/docker.sock without being root in the container.
RUN groupadd --gid 1000 aidev \
    && useradd --uid 1000 --gid aidev --create-home --shell /usr/sbin/nologin aidev

WORKDIR /app
COPY --from=build --chown=aidev:aidev /build/target/*.jar /app/ai-dev-platform.jar

USER aidev

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC" \
    SPRING_PROFILES_ACTIVE=json

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/ai-dev-platform.jar"]
