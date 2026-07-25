# LArtifactory

Artifact repository server built with Java 25, Javalin, HikariCP, Log4j2 and LUtils.

## Requirements

- JDK 25
- PostgreSQL (or adapt the JDBC driver in `gradle/libs.versions.toml`)

## Configuration

All configuration is read from environment variables:

| Variable                   | Default                                          | Description                  |
|----------------------------|--------------------------------------------------|------------------------------|
| `ARTIFACTORY_PORT`         | `7070`                                           | HTTP port                    |
| `ARTIFACTORY_DB_URL`       | `jdbc:postgresql://localhost:5432/artifactory`   | JDBC url                     |
| `ARTIFACTORY_DB_USERNAME`  | `artifactory`                                    | Database user                |
| `ARTIFACTORY_DB_PASSWORD`  | `artifactory`                                    | Database password            |
| `ARTIFACTORY_DB_POOL_SIZE` | `10`                                             | HikariCP maximum pool size   |

## Development

```bash
./gradlew run          # start the server
./gradlew test         # run the tests
./gradlew shadowJar    # build a fat jar into build/libs
```

`./gradlew compileJava` also regenerates `openapi.json` from the `@OpenApi` annotations.
Swagger UI is served at `/swagger`, the health endpoint at `/health`.

## Docker

```bash
docker build -t lartifactory .
docker run -p 7070:7070 lartifactory
```
