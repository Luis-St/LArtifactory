# LArtifactory
Artifact repository server built with Java 25, Javalin, HikariCP, Log4j2 and LUtils.
It hosts packages for Maven/Gradle, PyPI, npm, NuGet, Cargo, Docker/OCI images and generic files with token based authentication.
## Requirements

- JDK 25
- PostgreSQL (or adapt the JDBC driver in `gradle/libs.versions.toml`)

## Configuration
All configuration is read from environment variables:

| Variable                                   | Default                                        | Description                                                                                |
|--------------------------------------------|------------------------------------------------|--------------------------------------------------------------------------------------------|
| `ARTIFACTORY_PORT`                         | `8080`                                         | HTTP port                                                                                  |
| `ARTIFACTORY_BASE_URL`                     | derived from the request                       | Public url used in generated links (e.g. `https://repo.example.com`)                       |
| `ARTIFACTORY_HOST_REPOSITORIES`            |                                                | Serve repositories at the root of a host, e.g. `npm.example.com=npm-local`                 |
| `ARTIFACTORY_TRUST_PROXY`                  | `false`                                        | Honor `X-Forwarded-For/Proto/Host/Prefix` (only enable behind a trusted proxy)             |
| `ARTIFACTORY_ENABLE_API_DOCS`              | `false`                                        | Serve the (unauthenticated) OpenAPI spec and Swagger UI                                    |
| `ARTIFACTORY_STORAGE_PATH`                 | `data`                                         | Directory for the artifact files                                                           |
| `ARTIFACTORY_MAX_UPLOAD_SIZE_MB`           | `1024`                                         | Maximum size of a single artifact                                                          |
| `ARTIFACTORY_MAX_BUFFERED_UPLOAD_SIZE_MB`  | `256`                                          | Maximum size of an npm or Cargo publish request, these are parsed in memory                |
| `ARTIFACTORY_ADMIN_USERNAME`               | `admin`                                        | Name of the initial administrator                                                          |
| `ARTIFACTORY_ADMIN_PASSWORD`               | generated and logged on first start            | Password of the initial administrator (only used if no user exists yet)                    |
| `ARTIFACTORY_LOGIN_MAX_FAILURES`           | `10`                                           | Failed password logins per client address and user before the login is locked              |
| `ARTIFACTORY_LOGIN_LOCKOUT_MINUTES`        | `15`                                           | Duration of a login lockout                                                                |
| `ARTIFACTORY_SESSION_HOURS`                | `12`                                           | Lifetime of a web ui session                                                               |
| `ARTIFACTORY_SESSION_REMEMBER_DAYS`        | `30`                                           | Lifetime of a web ui session with "keep me signed in"                                      |
| `ARTIFACTORY_NPM_TOKEN_DAYS`               | `90`                                           | Lifetime of tokens created by `npm login`, `0` for no expiry                               |
| `ARTIFACTORY_MAINTENANCE_INTERVAL_MINUTES` | `60`                                           | Interval of the cleanup of orphaned blobs, temporary files and expired sessions            |
| `ARTIFACTORY_REMOTE_METADATA_TTL_MINUTES`  | `30`                                           | Age after which a remote repository fetches Maven metadata and snapshots again, `0` always |
| `ARTIFACTORY_AUDIT_RETENTION_DAYS`         | `365`                                          | Age after which audit log entries are deleted by the maintenance task, `0` keeps them      |
| `ARTIFACTORY_DB_URL`                       | `jdbc:postgresql://localhost:5432/artifactory` | JDBC url                                                                                   |
| `ARTIFACTORY_DB_USERNAME`                  | required (`artifactory` in the image)          | Database user                                                                              |
| `ARTIFACTORY_DB_PASSWORD`                  | required                                       | Database password                                                                          |
| `ARTIFACTORY_DB_POOL_SIZE`                 | `10`                                           | HikariCP maximum pool size                                                                 |
| `ARTIFACTORY_LOG_ALL`                      | `WARN`                                         | Log level of third-party libraries (Jetty, HikariCP), the server itself always logs `INFO` |

Without `ARTIFACTORY_BASE_URL` the public url is derived from the request. The client controlled `X-Forwarded-Host`,
`X-Forwarded-Proto` and `X-Forwarded-Prefix` headers are only trusted when `ARTIFACTORY_TRUST_PROXY=true` (otherwise the
`Host` header and the request scheme are used). **Set `ARTIFACTORY_BASE_URL` in production**, or only enable
`ARTIFACTORY_TRUST_PROXY` behind a reverse proxy that sets these headers, so generated download urls can not be
redirected to an attacker host via a forged `Host`/`X-Forwarded-Host` header.
The client address used for the login lockout is the last `X-Forwarded-For` entry,
because the proxy appends the address it received the request from and all earlier entries can be forged by the client.
Without `ARTIFACTORY_TRUST_PROXY` every client behind a reverse proxy has the address of the proxy.
The login lockout then locks all users out after the failures of anyone, and token ip allowlists and the addresses in the audit log only show the proxy.
The server logs a warning once when a request carries `X-Forwarded-For` while the option is off, because the misconfiguration is otherwise silent.
The database schema is created on startup and later schema changes are applied as versioned migrations (recorded in `_sql_migrations`).
Files are stored content addressed (sha256) below `ARTIFACTORY_STORAGE_PATH`.
A blob that no file references any more is deleted once it is older than one hour,
because an upload stores its blob before the file is committed to the database and a younger blob may still be in use.
The maintenance task removes such blobs, temporary files older than a day, expired sessions and audit log entries older than `ARTIFACTORY_AUDIT_RETENTION_DAYS`.
The database holds the upstream password of remote repositories and the signing secret of webhooks in plain text,
because both are needed in plain form to authenticate to the upstream and to sign deliveries, so they can not be hashed like passwords and tokens.
A database dump is therefore as sensitive as these credentials and has to be protected accordingly.
`GET /health` responds with `503` while the database is unreachable, so load balancers and orchestrators can detect it.
For production deployments, set a strong `ARTIFACTORY_ADMIN_PASSWORD` (user passwords created through the API must be at least 12 characters), a dedicated `ARTIFACTORY_DB_PASSWORD`, and leave `ARTIFACTORY_ENABLE_API_DOCS` disabled. Uploaded
artifacts are always served with `Content-Disposition: attachment` and `X-Content-Type-Options: nosniff` so they can not execute in the browser.
## Web UI
The server ships a web ui at `/ui/` (`/` redirects there). It shows an overview of the stored artifacts per repository type,
lets you browse packages, versions and files, and covers the configuration of repositories, users, permissions and tokens,
including copy-ready client setup snippets for every repository.
Administrators also get the audit log and can manage webhooks.
Users sign in with their password or an access token, and without signing in the ui can be browsed as a guest, which shows the public repositories only.
Signing in creates a session (`POST /api/session`) and only the session token is kept in the browser, never the password or the access token.
With "keep me signed in" the session is kept in `localStorage`, otherwise in `sessionStorage`, and signing out revokes it on the server.
A session created with an access token has the level of the token, ends when the token expires or is revoked and never grants administration,
because the token it was exchanged for does not grant it either.
Counts, sizes, the package list, the search and the folders of the file browser are computed and paged by the server,
so the ui never loads all packages and files of an instance.
Downloads use a single use ticket (`POST /api/downloads`) that authenticates one request of one path within a minute,
because a plain link carries no authorization header and the browser can then stream the file to disk instead of buffering it in memory.
The ui is a React single page application in `ui/`, built with Vite and packaged into the jar by Gradle (requires Node.js 20.19+):

```bash
./gradlew buildUi           # build the ui into ui/dist (also part of processResources)
./gradlew shadowJar -PskipUi # package an already built ui/dist without running npm
cd ui && npm run dev        # development server on http://localhost:5173/ui/ proxying the api to localhost:8080
```

## Repositories
Repositories are created with the management api, every repository has one of the types below and is served at `/{type}/{repository}/`.

| Type      | Client url                                                        | Notes                                                                |
|-----------|-------------------------------------------------------------------|----------------------------------------------------------------------|
| `maven`   | `{base}/maven/{repo}`                                             | Maven 2 layout, `maven-metadata.xml` and checksums are generated     |
| `generic` | `{base}/generic/{repo}/{path}`                                    | Arbitrary files with `PUT`/`GET`/`HEAD`/`DELETE`, directory listings |
| `pypi`    | `{base}/pypi/{repo}/simple/` (pip), `{base}/pypi/{repo}/` (twine) | PEP 503 / PEP 691 simple api and the legacy upload api               |
| `npm`     | `{base}/npm/{repo}/`                                              | publish, dist-tags, deprecate, unpublish, search, legacy login       |
| `nuget`   | `{base}/nuget/{repo}/v3/index.json`                               | V3 feed: flat container, registrations, search, push, unlist/relist  |
| `cargo`   | `sparse+{base}/cargo/{repo}/index/`                               | Sparse index: publish, download, yank/unyank, search, owners         |
| `oci`     | `{host}/{repo}/{image}` (docker), `{base}/v2/`                    | OCI distribution api: push, pull, tags, referrers, catalog, delete   |

Released versions are immutable: uploading a different file over an existing release returns `409 Conflict`
(Maven snapshots can always be redeployed, repositories can allow redeploys with `allowRedeploy`).
`npm` and `cargo` repositories reject `allowRedeploy`, because both ecosystems forbid overwriting a published version and their clients cache versions forever.
Checksum sidecars (`file.sha1`) are a Maven feature, a `generic` repository stores a file named `file.sha256` like any other file.

### Local, remote and virtual repositories

Besides normal local repositories, which store the artifacts that are published to them, a repository can be remote or virtual.
A remote repository proxies an upstream and optionally caches what is fetched.
A virtual repository aggregates several repositories and resolves reads across them in order.
Remote and virtual repositories are read-only and currently support only the path based `maven` and `generic` types.
Publishing to them answers `405 Method Not Allowed`, because the request is valid but the repository does not support the method.
The class of a repository is fixed when it is created, a virtual repository stays virtual even when its last member is deleted.

```bash
# A remote repository proxying Maven Central, caching downloads locally
curl -u admin:password -X POST https://repo.example.com/api/repositories -H 'Content-Type: application/json' \
	-d '{"name":"maven-central","type":"maven","remote":{"url":"https://repo1.maven.org/maven2","cacheEnabled":true}}'

# A virtual repository that serves releases, snapshots and the proxy under one url (first match wins)
curl -u admin:password -X POST https://repo.example.com/api/repositories -H 'Content-Type: application/json' \
	-d '{"name":"maven-all","type":"maven","members":["releases","snapshots","maven-central"]}'
```

A remote repository fetches a missing artifact from `{upstream}/{path}`.
With `cacheEnabled` the fetched artifact is stored locally on first access.
Released artifacts are immutable and stay cached, while `maven-metadata.xml`, its checksums and snapshots change upstream,
so they are fetched again once the cached copy is older than `ARTIFACTORY_REMOTE_METADATA_TTL_MINUTES`.
If the upstream does not answer, the stale copy is served, because an outdated version list is more useful than a failed build.
Upstream files are streamed to storage instead of buffered, a file larger than the max upload size is rejected by its `Content-Length` before the download.
A virtual repository resolves each read against its members, local or remote, in order and serves the first match,
including the generated metadata, checksums and non-unique snapshot names of local members.
`maven-metadata.xml` is the exception, it is merged across all members so the version list contains the versions of every member.
Snapshot level metadata can not be merged, because build numbers are local to a repository, so the most recently updated one is served.
A virtual repository only resolves members the caller can read.
A public virtual repository with a private member therefore serves that member to its readers only,
because otherwise one toggle on the virtual repository would publish the private repository to everyone.
A cached file of a remote repository can be deleted with `DELETE` and the `DELETE` level, for example to drop a wrong artifact.
It is fetched from the upstream again on the next request.
The upstream settings and the members can be changed with `PATCH /api/repositories/{name}`,
omitted fields of `remote` keep their stored value and an empty `username` removes the upstream credentials.


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

**Docker / OCI** (the first component of the image name is the repository)

```bash
docker login repo.example.com -u ci
docker tag my-image:1.0 repo.example.com/oci-local/team/my-image:1.0
docker push repo.example.com/oci-local/team/my-image:1.0
```

### OCI images
Docker only talks to `/v2/` at the root of a host and knows no path prefix,
so the OCI distribution api is served at `{base}/v2/{repo}/{image}/...` and the repository is the first component of the image name.
On a host mapped to an `oci` repository with `ARTIFACTORY_HOST_REPOSITORIES` the repository component is dropped (`docker.example.com/team/my-image`),
and `{base}/oci/{repo}/v2/` serves the same api for clients that support a path prefix.
Docker requires https for every registry except `localhost`, so a production server needs TLS in front of it or an `insecure-registries` entry in the Docker daemon.
Images, multi-arch indexes and other OCI artifacts (e.g. pushed with `oras`) are supported,
any OCI client works because the api follows the distribution spec instead of the Docker tooling.

Blobs (layers and configs) are stored once per repository and shared by all of its images, so a cross-image mount always succeeds.
A manifest is stored as version `sha256:{digest}` of the package `{image}` and tags are its dist tags,
which makes images, versions and tags show up in the web ui like the packages of the other types.
Tags are immutable like releases, moving an existing tag to another manifest returns `409 Conflict` unless the repository allows redeploys.
This is stricter than Docker Hub, but a moved tag silently changes what a deployment pulls, so it is opt-in per repository like every other redeploy.
Deleting a manifest removes its tags, its layers stay until the maintenance task removes blobs no manifest references once they are older than a day.
The age protects a push in progress, which uploads all blobs before its manifest.
Chunked uploads are kept in memory and in a temporary file, an upload interrupted by a restart of the server has to be started again.
A blob that a manifest still references can not be deleted (`409`), because the image would be broken while its manifest is still served.

The registry uses the token flow of the distribution spec: an anonymous request is answered with a `Bearer` challenge pointing to `{base}/v2/token`.
A `Basic` challenge does not work, the Docker engine then aborts every request without stored credentials, so an anonymous pull from a `publicRead` repository would fail.
Without credentials the token endpoint hands out an anonymous token, which reads public repositories.
With credentials it returns the access token itself or, for a password, a session that is valid for one hour, so the password is never sent again.
That session grants repository access like a token of the `DELETE` level but no administration,
because a credential stored by the Docker engine should not be able to manage the server.
Basic auth on the api endpoints keeps working for clients such as `curl`.

### One host per ecosystem
With `ARTIFACTORY_HOST_REPOSITORIES` a repository can be served at the root of its own host, which allows urls like
`https://npm.example.com/@scope%2fname` or `https://pypi.example.com/simple/name/`:

```
ARTIFACTORY_HOST_REPOSITORIES=pypi.example.com=pypi-local,npm.example.com=npm-local,nuget.example.com=nuget-local,cargo.example.com=cargo-local
```

All hosts point to the same server (e.g. via nginx), the management api, `/health`, `/ui` and the api docs keep working on every host.
Only these exact paths are taken from the repository, so packages such as `healthcheck`, `swagger-ui` or a Maven group `api.example` stay reachable.
The mapping matches the `Host` header; if the proxy rewrites `Host` and forwards the original in `X-Forwarded-Host`,
set `ARTIFACTORY_TRUST_PROXY=true` so that header is used.
## Authentication

- Users authenticate with their password (basic auth) or with tokens.
- Tokens are created with `POST /api/tokens` and can be used as password (basic auth with the user name of the token owner or `__token__`),
  as bearer token (npm), as `X-NuGet-ApiKey` (NuGet) or as raw `Authorization` header (Cargo).
  A token can be limited to a lower access level than its user and can expire.
  A token created without `level` gets `READ`, because a caller that forgets the field should get the least access and not the most.
  Tokens may additionally be restricted
  to an IP allowlist (`allowedIps`, a comma or whitespace separated list of IPs or CIDR ranges) and to a per minute
  request rate limit (`rateLimitPerMinute`); the server records when and from which IP each token was last used.
- Access levels per repository: `READ` (download), `WRITE` (publish, tags, yank, unlist) and `DELETE` (delete, unpublish).
  Permissions are granted per repository or for all repositories with `*`. Administrators have full access.
- Administration and token management require the user password (basic auth) or a web ui session created with it.
  Tokens, including an admin's token, and sessions created with a token grant repository access only.
  They can not administer users, permissions or repositories and can not create, list or revoke tokens,
  so a leaked short-lived token can not be turned into a token that never expires.
  `GET /api/me`, the read endpoints of the repositories and `DELETE /api/repositories/{name}/packages` accept tokens,
  and the read endpoints answer anonymous callers for public repositories, which is what the guest mode of the ui uses.
- Failed password logins are counted per client address and user.
  After `ARTIFACTORY_LOGIN_MAX_FAILURES` failures the login responds with `429` for `ARTIFACTORY_LOGIN_LOCKOUT_MINUTES`,
  and an address is locked for all users after five times as many failures.
  Behind a reverse proxy the client address is only taken from `X-Forwarded-For` with `ARTIFACTORY_TRUST_PROXY=true`.
- Tokens created by `npm login` have the `WRITE` level and expire after `ARTIFACTORY_NPM_TOKEN_DAYS`, run `npm login` again afterwards.
  Publishing needs `WRITE` only, `npm unpublish` needs a token of the `DELETE` level created on the tokens page.
- Changing a password ends all other sessions of the user and keeps the tokens, because CI jobs depend on them.
  `revokeTokens` revokes the tokens in the same request, for a password change after a compromise.
- User names are unique without regard to case, because look-alike names such as `Admin` and `admin` mislead in permission lists.
- The last administrator can not lose the administrator privileges, so the server can not be locked out of its own management.
- Repositories are private by default, `publicRead` allows anonymous downloads.

## Management API

| Method                 | Path                                          | Description                                                                               |
|------------------------|-----------------------------------------------|-------------------------------------------------------------------------------------------|
| `GET`                  | `/api/me`                                     | The authenticated user                                                                    |
| `POST` `DELETE`        | `/api/session`                                | Sign in with the password or a token / sign out (web ui session)                          |
| `GET` `POST`           | `/api/repositories`                           | List / create repositories                                                                |
| `GET` `PATCH` `DELETE` | `/api/repositories/{name}`                    | Get / update / delete a repository                                                        |
| `GET` `DELETE`         | `/api/repositories/{name}/packages`           | List packages and versions (`?q=&offset=&limit=`) / delete a package version              |
| `GET`                  | `/api/repositories/{name}/files`              | List files (`?prefix=&offset=&limit=`)                                                    |
| `GET`                  | `/api/repositories/{name}/packages/{package}` | One package with its versions, tags and files                                             |
| `GET`                  | `/api/repositories/{name}/tree`               | Direct content of a folder (`?prefix=`)                                                   |
| `GET`                  | `/api/summary`                                | Content counts per readable repository, newest versions, activity (`?repository=`)        |
| `GET`                  | `/api/packages`                               | Packages of all readable repositories (`?type=&repository=&q=&sort=&dir=&offset=&limit=`) |
| `GET`                  | `/api/files`                                  | Files of all readable repositories (`?type=&repository=&q=&sort=&dir=&offset=&limit=`)    |
| `GET`                  | `/api/search`                                 | Packages of all readable repositories by name (`?q=&limit=`)                              |
| `POST`                 | `/api/downloads`                              | Single use download ticket for one path                                                   |
| `GET` `POST`           | `/api/users`                                  | List / create users                                                                       |
| `PATCH` `DELETE`       | `/api/users/{username}`                       | Update (password, admin, revokeTokens) / delete a user                                    |
| `GET` `PUT` `DELETE`   | `/api/permissions`                            | List / grant / revoke permissions                                                         |
| `GET` `POST`           | `/api/tokens`                                 | List / create tokens                                                                      |
| `DELETE`               | `/api/tokens/{id}`                            | Revoke a token                                                                            |
| `GET`                  | `/api/audit`                                  | List audit log entries (admin, `?repository=&actor=&action=&from=&to=&limit=&offset=`)    |
| `GET` `POST`           | `/api/webhooks`                               | List / create webhooks (admin)                                                            |
| `GET` `PATCH` `DELETE` | `/api/webhooks/{id}`                          | Get / update / delete a webhook (admin)                                                   |
| `POST`                 | `/api/webhooks/{id}/test`                     | Send a test ping and return the delivery result (admin)                                   |
| `GET`                  | `/api/webhooks/{id}/deliveries`               | List recent delivery attempts of a webhook (admin)                                        |

Users changing their own password also send `currentPassword`,
so a stolen web ui session alone can not be used to take over the account.
The package and file lists are paged, the total number of entries is returned in the `X-Total-Count` header.
A page holds at most 500 packages or 5000 files, so a single request can not load a whole large repository into memory.
The audit log and the cross repository package and file lists are paged the same way.
Errors of the management api are an `ErrorResponse` json object, the package protocols keep the plain text errors their clients expect.
A remote repository is created and updated with `remote.url`, `remote.cacheEnabled`, `remote.username` and `remote.password`,
the password is never returned, the response only says whether one is stored (`hasPassword`).
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

### Audit log

The audit log records administrative actions (repositories, users, permissions, tokens, webhooks and package deletions),
failed logins and the start of a lockout, and artifacts published or deleted through the package protocols.
Locked out attempts are not recorded one by one, because a flood of attempts would otherwise flood the table.
Entries older than `ARTIFACTORY_AUDIT_RETENTION_DAYS` are deleted by the maintenance task.

### Webhooks

Webhooks notify external services when something changes. Each webhook has a target url, an optional repository scope
(empty means all repositories), an optional set of event types (empty means all) and an optional signing secret.

Events: `artifact.published`, `artifact.deleted`, `package.deleted`, `repository.created`, `repository.deleted`.

Deliveries are sent asynchronously as a JSON `POST` and retried a few times, the body is
`{"event":..., "repository":..., "target":..., "timestamp":...}`.
Each request carries `X-LArtifactory-Event`, `X-LArtifactory-Delivery` and, when a secret is configured,
`X-LArtifactory-Signature: sha256=<hmac>` (HMAC-SHA256 of the raw body).
All attempts of one delivery carry the same `X-LArtifactory-Delivery` id, so a receiver can recognize a retry.
Four deliveries run in parallel, so one slow target does not hold up the others, and an event that does not fit into the full queue is logged.
Webhooks scoped to a repository are deleted together with the repository.
The url, the events, the repository scope and the secret can be changed with `PATCH /api/webhooks/{id}`, an empty `secret` removes it.
The last delivery attempts are recorded and can be inspected in the web UI or via the API.

## Development

```bash
./gradlew run          # start the server
./gradlew test         # run the unit and integration tests
./gradlew shadowJar    # build a fat jar into build/libs
```

`./gradlew compileJava` also regenerates `openapi.json` from the `@OpenApi` annotations.
The GitHub workflow in `.github/workflows/build.yml` runs the type check of the ui and all tests on every push and pull request.
The integration tests start the server against a PostgreSQL container with Testcontainers and are skipped if no docker daemon is available.
Swagger UI is served at `/swagger`, the health endpoints below are served at `/health`.

### Health probes

| Path            | Checks                 | Status codes                                     | Use for                                |
|-----------------|------------------------|--------------------------------------------------|----------------------------------------|
| `/health`       | database (aggregate)   | `200` when the database is reachable, else `503` | dashboards, load balancers             |
| `/health/live`  | nothing (process only) | `200`                                            | liveness probe (restart the container) |
| `/health/ready` | database               | `200` when ready, `503` when not                 | readiness probe (route traffic)        |

Liveness never touches the database, so a database outage does not cause the container to be restarted.
Readiness returns `503` while the database is unreachable, so load balancers stop sending traffic until it recovers.
### Repository tests
`test/` contains a package for every repository type and a script that publishes, downloads, lists and installs them
with the native clients (gradle, mvn, twine, pip, npm, dotnet, cargo, docker) against a running server:

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
docker run -p 8080:8080 -v lartifactory-data:/data -e ARTIFACTORY_DB_PASSWORD=... -e ARTIFACTORY_ADMIN_PASSWORD=... lartifactory
```

The container runs as the unprivileged user `artifactory` (uid and gid `10001`) and checks `/health` with a docker health check.
A data volume created by an older image belongs to root and has to be handed over once,
for example with `docker run --rm -v lartifactory-data:/data alpine chown -R 10001:10001 /data`.
The database password has no default and is required.
The heap is sized to 75 % of the container memory limit (`JAVA_TOOL_OPTIONS`), override it to change the heap size.
