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

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

ENV ARTIFACTORY_PORT=8080
ENV ARTIFACTORY_DB_URL=jdbc:postgresql://localhost:5432/artifactory
ENV ARTIFACTORY_DB_USERNAME=artifactory
ENV ARTIFACTORY_DB_PASSWORD=artifactory
ENV ARTIFACTORY_DB_POOL_SIZE=10
ENV ARTIFACTORY_STORAGE_PATH=/data

VOLUME /data
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
