FROM eclipse-temurin:25-jdk AS build

WORKDIR /app
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
COPY src src

RUN chmod +x gradlew && ./gradlew shadowJar --no-daemon

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
