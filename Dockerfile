FROM node:22-alpine AS ui

WORKDIR /ui
COPY ui/package.json ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY ui ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS build

WORKDIR /app
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src
COPY --from=ui /ui/dist ui/dist

RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon -PskipUi

FROM eclipse-temurin:25-jre

# curl is only used by the health check
RUN apt-get update \
	&& apt-get install -y --no-install-recommends curl \
	&& rm -rf /var/lib/apt/lists/* \
	&& groupadd --system --gid 10001 artifactory \
	&& useradd --system --uid 10001 --gid artifactory --home-dir /app --shell /usr/sbin/nologin artifactory \
	&& mkdir -p /data \
	&& chown artifactory:artifactory /data

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# The database password has no default, pass ARTIFACTORY_DB_PASSWORD (and ARTIFACTORY_ADMIN_PASSWORD) at runtime
ENV ARTIFACTORY_PORT=8080
ENV ARTIFACTORY_DB_URL=jdbc:postgresql://localhost:5432/artifactory
ENV ARTIFACTORY_DB_USERNAME=artifactory
ENV ARTIFACTORY_DB_POOL_SIZE=10
ENV ARTIFACTORY_STORAGE_PATH=/data
# Size the heap from the container memory limit instead of the host memory
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

USER artifactory
VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
	CMD curl -fsS "http://localhost:${ARTIFACTORY_PORT}/health" > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
