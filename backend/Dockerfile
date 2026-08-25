# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# The memory service — multi-stage build.
#
# stage 1  Maven and a JDK build the Quarkus fast-jar. Only the POM is copied
#          first, so the dependency layer survives a source change that does
#          not touch dependencies.
# stage 2  A JRE carrying the fast-jar payload and nothing else.
#
# The image contains no credential and no hostname. Everything the service
# needs to reach a database arrives as an environment variable at run time —
# see application.properties for the MEMORY_* names. That includes BOTH
# connections: the service connects as its own unprivileged role, and Flyway
# connects as a different, CREATEROLE one. An image that baked either would
# put a password in a public registry.
# ---------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /work

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline -DskipTests || true

COPY src src

# Tests are not run here. Every load-bearing test in this service starts a
# real PostgreSQL through Testcontainers, so running the suite inside an image
# build would mean either docker-in-docker or a quietly reduced suite — and a
# reduced suite here is one that drops exactly the row-level-security and
# privilege probes the service exists to hold. The pipeline runs the full
# suite before the image is built, and the release workflow runs it again at
# the tag.
RUN mvn -B -ntp package -DskipTests

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /work/target/quarkus-app/lib/     /app/lib/
COPY --from=build /work/target/quarkus-app/*.jar    /app/
COPY --from=build /work/target/quarkus-app/app/     /app/app/
COPY --from=build /work/target/quarkus-app/quarkus/ /app/quarkus/

# The health routes, and nothing else. There is no REST extension in this
# service, so this port serves two paths under /q/health and 404s everything
# else. It is deliberately not published at the edge.
EXPOSE 8080
ENV LANG=en_US.UTF-8 LANGUAGE=en_US:en
USER 1000

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
