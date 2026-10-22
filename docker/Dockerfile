# syntax=docker/dockerfile:1

# ---- UI stage: React console ----
FROM node:25-alpine AS ui
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Cache dependencies separately from sources
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
COPY --from=ui /ui/dist ./frontend/dist
# The UI is already built in the stage above, so Maven skips its own Node.js steps
RUN mvn -B -q package -DskipTests -Dskip.npm -Dskip.installnodenpm \
 && java -Djarmode=layertools -jar target/openmesh-gateway-*.jar extract --destination target/layers

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="openmesh-gateway" \
      org.opencontainers.image.description="Cloud-native multi-tenant API gateway and traffic control plane"

# Fixed numeric UID so Kubernetes can enforce runAsNonRoot
RUN addgroup -S -g 10001 openmesh && adduser -S -u 10001 -G openmesh -H -s /sbin/nologin openmesh
WORKDIR /app

# Spring Boot layers (dependencies change rarely, app code often). Files stay root-owned and read-only for the app user.
COPY --from=build /workspace/target/layers/dependencies/ ./
COPY --from=build /workspace/target/layers/spring-boot-loader/ ./
COPY --from=build /workspace/target/layers/snapshot-dependencies/ ./
COPY --from=build /workspace/target/layers/application/ ./

USER 10001
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
