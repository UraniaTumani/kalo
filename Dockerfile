# Backend image.
#
# Tests are deliberately NOT run here: they need a Docker daemon for
# Testcontainers, which is not available inside a build container. CI runs them
# before the image is built (see .github/workflows/ci.yml), so an image can only
# be produced from a commit whose suite passed.

# ---------------------------------------------------------------- build stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies resolve from the POM alone, so this layer is cached until the
# POM itself changes rather than on every source edit.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ---------------------------------------------------------------- run stage
FROM eclipse-temurin:21-jre-alpine

# Runs unprivileged: nothing in this application needs root, and a container
# escape should not land on one.
RUN addgroup -S kalo && adduser -S kalo -G kalo

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
RUN chown kalo:kalo /app/app.jar

USER kalo
EXPOSE 8080

# Container memory is usually smaller than the host the JVM would infer from.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
