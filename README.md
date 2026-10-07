# LArtifactory

Artifact repository server built with Java 25, Javalin, HikariCP, Log4j2 and LUtils.
It hosts packages for Maven/Gradle, PyPI, npm, NuGet, Cargo and generic files with token based authentication.

## Requirements

- JDK 25
- PostgreSQL (or adapt the JDBC driver in `gradle/libs.versions.toml`)

## Configuration

All configuration is read from environment variables:

| Variable                         | Default                                        | Description                                                                 |
|----------------------------------|------------------------------------------------|-----------------------------------------------------------------------------|
| `ARTIFACTORY_PORT`               | `8080`                                         | HTTP port                                                                   |
| `ARTIFACTORY_BASE_URL`           | derived from the request                       | Public url used in generated links (e.g. `https://repo.example.com`)        |
| `ARTIFACTORY_HOST_REPOSITORIES`  |                                                | Serve repositories at the root of a host, e.g. `npm.example.com=npm-local`  |
| `ARTIFACTORY_TRUST_PROXY`        | `false`                                        | Honor `X-Forwarded-Proto/Host/Prefix` (only enable behind a trusted proxy)  |
| `ARTIFACTORY_ENABLE_SWAGGER`     | `true`                                         | Serve the (unauthenticated) OpenAPI spec and Swagger UI                     |
| `ARTIFACTORY_STORAGE_PATH`       | `data`                                         | Directory for the artifact files                                            |
| `ARTIFACTORY_MAX_UPLOAD_SIZE_MB` | `1024`                                         | Maximum size of a single artifact                                           |
| `ARTIFACTORY_ADMIN_USERNAME`     | `admin`                                        | Name of the initial administrator                                           |
| `ARTIFACTORY_ADMIN_PASSWORD`     | generated and logged on first start            | Password of the initial administrator (only used if no user exists yet)     |
| `ARTIFACTORY_DB_URL`             | `jdbc:postgresql://localhost:5432/artifactory` | JDBC url                                                                    |
| `ARTIFACTORY_DB_USERNAME`        | `artifactory`                                  | Database user                                                               |
| `ARTIFACTORY_DB_PASSWORD`        | `artifactory`                                  | Database password                                                           |
| `ARTIFACTORY_DB_POOL_SIZE`       | `10`                                           | HikariCP maximum pool size                                                  |

Without `ARTIFACTORY_BASE_URL` the public url is derived from the request. The client controlled `X-Forwarded-Host`,
`X-Forwarded-Proto` and `X-Forwarded-Prefix` headers are only trusted when `ARTIFACTORY_TRUST_PROXY=true` (otherwise the
`Host` header and the request scheme are used). **Set `ARTIFACTORY_BASE_URL` in production**, or only enable
`ARTIFACTORY_TRUST_PROXY` behind a reverse proxy that sets these headers, so generated download urls can not be
redirected to an attacker host via a forged `Host`/`X-Forwarded-Host` header.
The database schema is created on startup, files are stored content addressed (sha256) below `ARTIFACTORY_STORAGE_PATH`.

## Repositories

Repositories are created with the management api, every repository has one of the types below and is served at `/{type}/{repository}/`.

| Type      | Client url                                             | Notes                                                                    |
|-----------|--------------------------------------------------------|--------------------------------------------------------------------------|
| `maven`   | `{base}/maven/{repo}`                                  | Maven 2 layout, `maven-metadata.xml` and checksums are generated         |
| `generic` | `{base}/generic/{repo}/{path}`                         | Arbitrary files with `PUT`/`GET`/`HEAD`/`DELETE`, directory listings     |
| `pypi`    | `{base}/pypi/{repo}/simple/` (pip), `{base}/pypi/{repo}/` (twine) | PEP 503 / PEP 691 simple api and the legacy upload api        |
| `npm`     | `{base}/npm/{repo}/`                                   | publish, dist-tags, deprecate, unpublish, search, legacy login           |
| `nuget`   | `{base}/nuget/{repo}/v3/index.json`                    | V3 feed: flat container, registrations, search, push, unlist/relist      |
| `cargo`   | `sparse+{base}/cargo/{repo}/index/`                    | Sparse index: publish, download, yank/unyank, search, owners             |

Released versions are immutable: uploading a different file over an existing release returns `409 Conflict`
(Maven snapshots can always be redeployed, repositories can allow redeploys with `allowRedeploy`).

### Client configuration

**Gradle**
```kotlin
repositories {
	maven {
		url = uri("https://repo.example.com/maven/releases")
		credentials {
			username = "ci"
			password = "<token>"
		}
	}
}
```

**Maven** (`settings.xml` server credentials with the repository id, the token is the password)
```xml
<server>
	<id>lartifactory</id>
	<username>ci</username>
	<password>token</password>
</server>
```

**pip / twine**
```bash
pip install --index-url https://ci:<token>@repo.example.com/pypi/pypi-local/simple/ my-package
twine upload --repository-url https://repo.example.com/pypi/pypi-local/ -u __token__ -p <token> dist/*
```

**npm** (`.npmrc`)
```ini
registry=https://repo.example.com/npm/npm-local/
//repo.example.com/npm/npm-local/:_authToken=<token>
```

**NuGet**
```bash
dotnet nuget push package.nupkg --source https://repo.example.com/nuget/nuget-local/v3/index.json --api-key <token>
```
For restores add the source with `packageSourceCredentials` (user name and token) to `nuget.config`.

**Cargo** (`.cargo/config.toml`)
```toml
[registries.lartifactory]
index = "sparse+https://repo.example.com/cargo/cargo-local/index/"
```
```bash
cargo login --registry lartifactory <token>
cargo publish --registry lartifactory
```

### One host per ecosystem

With `ARTIFACTORY_HOST_REPOSITORIES` a repository can be served at the root of its own host, which allows urls like
`https://npm.example.com/@scope%2fname` or `https://pypi.example.com/simple/name/`:

```
ARTIFACTORY_HOST_REPOSITORIES=pypi.example.com=pypi-local,npm.example.com=npm-local,nuget.example.com=nuget-local,cargo.example.com=cargo-local
```

All hosts point to the same server (e.g. via nginx), `/api`, `/health` and `/swagger` keep working on every host.
The mapping matches the `Host` header; if the proxy rewrites `Host` and forwards the original in `X-Forwarded-Host`,
set `ARTIFACTORY_TRUST_PROXY=true` so that header is used.

## Authentication

- Users authenticate with their password (basic auth) or with tokens.
- Tokens are created with `POST /api/tokens` and can be used as password (basic auth, any user name or `__token__`),
  as bearer token (npm), as `X-NuGet-ApiKey` (NuGet) or as raw `Authorization` header (Cargo).
  A token can be limited to a lower access level than its user and can expire.
- Access levels per repository: `READ` (download), `WRITE` (publish, tags, yank, unlist) and `DELETE` (delete, unpublish).
  Permissions are granted per repository or for all repositories with `*`. Administrators have full access.
- The management api (`/api/*`) requires authenticating with the user password (basic auth); tokens (including an
  admin's token) grant repository access only and can not be used to administer users, permissions or repositories.
- Repositories are private by default, `publicRead` allows anonymous downloads.

## Management API

| Method                  | Path                                  | Description                                            |
|-------------------------|---------------------------------------|--------------------------------------------------------|
| `GET`                   | `/api/me`                             | The authenticated user                                 |
| `GET` `POST`            | `/api/repositories`                   | List / create repositories                             |
| `GET` `PATCH` `DELETE`  | `/api/repositories/{name}`            | Get / update / delete a repository                     |
| `GET` `DELETE`          | `/api/repositories/{name}/packages`   | List packages and versions / delete a package version  |
| `GET`                   | `/api/repositories/{name}/files`      | List files (`?prefix=`)                                |
| `GET` `POST`            | `/api/users`                          | List / create users                                    |
| `PATCH` `DELETE`        | `/api/users/{username}`               | Update (password, admin) / delete a user               |
| `GET` `PUT` `DELETE`    | `/api/permissions`                    | List / grant / revoke permissions                      |
| `GET` `POST`            | `/api/tokens`                         | List / create tokens                                   |
| `DELETE`                | `/api/tokens/{id}`                    | Revoke a token                                         |

The full api is documented in `openapi.json` and served by Swagger UI at `/swagger`.

```bash
curl -u admin:password -X POST https://repo.example.com/api/repositories -H 'Content-Type: application/json' \
	-d '{"name": "releases", "type": "maven", "description": "Release builds"}'
curl -u admin:password -X POST https://repo.example.com/api/users -H 'Content-Type: application/json' \
	-d '{"username": "ci", "password": "a-long-password"}'
curl -u admin:password -X PUT https://repo.example.com/api/permissions -H 'Content-Type: application/json' \
	-d '{"repository": "*", "username": "ci", "level": "WRITE"}'
curl -u ci:a-long-password -X POST https://repo.example.com/api/tokens -H 'Content-Type: application/json' \
	-d '{"name": "github actions", "level": "WRITE"}'
```

## Development

```bash
./gradlew run          # start the server
./gradlew test         # run the unit tests
./gradlew shadowJar    # build a fat jar into build/libs
```

`./gradlew compileJava` also regenerates `openapi.json` from the `@OpenApi` annotations.
Swagger UI is served at `/swagger`, the health endpoint at `/health`.

### Repository tests

`test/` contains a package for every repository type and a script that publishes, downloads, lists and installs them
with the native clients (gradle, mvn, twine, pip, npm, dotnet, cargo) against a running server:

```bash
docker compose -f docker-compose.db.yml up -d
./gradlew run
ARTIFACTORY_URL=http://localhost:8080 ARTIFACTORY_ADMIN_PASSWORD=admin ./test/run.sh            # all types
ARTIFACTORY_URL=http://localhost:8080 ARTIFACTORY_ADMIN_PASSWORD=admin ./test/run.sh npm cargo  # selected types
```

The script recreates the test repositories and the users `lat-ci` and `lat-reader`, so do not run it against a production server.

## Docker

```bash
docker build -t lartifactory .
docker run -p 8080:8080 -v lartifactory-data:/data -e ARTIFACTORY_ADMIN_PASSWORD=... lartifactory
```
