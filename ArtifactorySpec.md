# Artifact Server — Endpoint Payload Templates

Companion to the protocol spec (`artifact-server-spec.md`) and implementation spec
(`artifact-server-implementation-spec.md`). Wire-level request/response templates for
every endpoint. Every variable part is a `{NamedPattern}` defined in the glossary
below; substitute per package/ecosystem.

Conventions:
- Binary bodies shown as `<binary … bytes>`.
- Truncated hex/base64 shown with `…` — treat as full length.
- Hostnames are illustrative subdomains; nginx terminates TLS in front (impl spec §9).

---

## 0. Placeholder glossary

### Infrastructure
| Variable | Meaning | Example |
|---|---|---|
| `{Host}` | server hostname | `maven.luis-st.net` |
| `{Repo}` | Maven repository name | `releases` / `snapshots` |
| `{Realm}` | auth challenge realm | `maven-releases` |
| `{Size}` | `Content-Length` in bytes | `48213` |

### Credentials
| Variable | Meaning | Example |
|---|---|---|
| `{BasicCreds}` | base64 of `user:password` | `Y2k6c2VjcmV0` |
| `{PypiBasic}` | base64 of `__token__:{Token}` | `X190b2tlbl9fOnB5cGkt…` |
| `{Token}` | bearer token (npm) | `npm_AbCdEf0123…` |
| `{ApiKey}` | NuGet API key | `oy2abc0123def…` |
| `{CargoToken}` | raw Cargo token (no scheme) | `ci-token-abc123` |

### Coordinates — Maven
| Variable | Meaning | Example |
|---|---|---|
| `{GroupId}` | group, dotted | `net.luisst` |
| `{GroupPath}` | group, slashed | `net/luisst` |
| `{ArtifactId}` | artifact id | `toolkit` |
| `{Classifier}` | optional classifier | `sources` |
| `{Ext}` | file extension | `jar` / `pom` / `module` |
| `{FileName}` | `{ArtifactId}-{Version}[-{Classifier}].{Ext}` | `toolkit-1.0.7.jar` |

### Coordinates — package ecosystems
| Variable | Meaning | Example |
|---|---|---|
| `{Package}` | package/library name (npm scoped = `@{Scope}/{Name}`) | `luis-toolkit`, `@luisst/toolkit` |
| `{Scope}` | npm scope (no `@`) | `luisst` |
| `{Name}` | bare name | `toolkit` |
| `{EncodedPackage}` | npm name with `/` → `%2f` | `@luisst%2ftoolkit` |
| `{NormalizedName}` | PyPI PEP 503 normalized name | `luis-toolkit` |
| `{PackageId}` | NuGet id (original case) | `Luisst.Toolkit` |
| `{LowerId}` | NuGet id lowercased | `luisst.toolkit` |
| `{Crate}` | Cargo crate name | `luisst-toolkit` |
| `{IndexPath}` | Cargo length-based index path | `lu/is/luisst-toolkit` |

### Versions
| Variable | Meaning | Example |
|---|---|---|
| `{Version}` | release version | `1.0.7` |
| `{LowerVersion}` | NuGet version lowercased | `1.0.7` |
| `{BaseVersion}` | Maven snapshot base | `1.1.0` |
| `{Timestamp}` | snapshot timestamp | `20260120.143000` |
| `{BuildNumber}` | snapshot build counter | `7` |
| `{SnapshotVersion}` | `{BaseVersion}-{Timestamp}-{BuildNumber}` | `1.1.0-20260120.143000-7` |
| `{LastUpdated}` | metadata timestamp | `20260120143000` |
| `{IsoTime}` | ISO-8601 publish time | `2026-01-20T14:30:00.000Z` |
| `{DistTag}` / `{TagVersion}` | npm tag name + its version | `beta` / `1.1.0-beta.1` |

### Files
| Variable | Meaning | Example |
|---|---|---|
| `{WheelName}` | PyPI wheel | `luis_toolkit-1.0.7-py3-none-any.whl` |
| `{SdistName}` | PyPI sdist | `luis_toolkit-1.0.7.tar.gz` |
| `{TarballName}` | npm tarball | `toolkit-1.0.7.tgz` |
| `{NupkgName}` | NuGet package = `{LowerId}.{LowerVersion}.nupkg` | `luisst.toolkit.1.0.7.nupkg` |
| `{CrateFile}` | Cargo crate | `luisst-toolkit-1.0.7.crate` |

### Digests
| Variable | Meaning | Example |
|---|---|---|
| `{Sha256}` | sha256 hex | `e3b0c4…` |
| `{Sha1}` | sha1 hex | `a94a8f…` |
| `{Md5}` | md5 hex | `d41d8c…` |
| `{Integrity}` | npm SRI (sha512, base64) | `sha512-9a1c…==` |
| `{Cksum}` | Cargo sha256 of the `.crate` | `9a1c…` |

### Dependencies & upload metadata
| Variable | Meaning | Example |
|---|---|---|
| `{DepGroupId}` `{DepArtifactId}` `{DepVersion}` | Maven dependency | `io.javalin` / `javalin` / `6.1.3` |
| `{DepName}` `{DepRange}` | npm dependency | `lodash` / `^4.17.21` |
| `{DepName}` `{DepReq}` | Cargo dependency | `serde` / `^1.0` |
| `{RequiresPython}` | PyPI python constraint | `>=3.9` |
| `{FileType}` `{PyTag}` `{MetadataVersion}` | PyPI upload fields | `bdist_wheel` / `py3` / `2.1` |
| `{Rev}` | npm document revision | `3-a1b2c3` |
| `{DlBase}` `{ApiBase}` | Cargo config URLs | `https://{Host}/api/v1/crates` / `https://{Host}` |

---

## 1. Maven / Gradle

### 1.1 GET artifact
```http
GET /maven/{Repo}/{GroupPath}/{ArtifactId}/{Version}/{FileName} HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/java-archive
Content-Length: {Size}
ETag: "{Sha256}"
Accept-Ranges: bytes

<binary jar bytes>
```

### 1.2 HEAD (existence check before resolve/deploy)
```http
HEAD /maven/{Repo}/{GroupPath}/{ArtifactId}/{Version}/{FileName} HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/java-archive
Content-Length: {Size}
ETag: "{Sha256}"
```
(Not found → `404 Not Found`, no body.)

### 1.3 PUT artifact — unauthenticated, then retried with credentials
```http
PUT /maven/{Repo}/{GroupPath}/{ArtifactId}/{Version}/{FileName} HTTP/1.1
Host: {Host}
Content-Type: application/java-archive
Content-Length: {Size}

<binary jar bytes>
```
```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Basic realm="{Realm}"
```
Retry (`{BasicCreds}` = base64 of `user:password`):
```http
PUT /maven/{Repo}/{GroupPath}/{ArtifactId}/{Version}/{FileName} HTTP/1.1
Host: {Host}
Authorization: Basic {BasicCreds}
Content-Type: application/java-archive
Content-Length: {Size}

<binary jar bytes>
```
```http
HTTP/1.1 201 Created
```

### 1.4 PUT sibling checksum (one per algorithm the client sends)
```http
PUT /maven/{Repo}/{GroupPath}/{ArtifactId}/{Version}/{FileName}.sha1 HTTP/1.1
Host: {Host}
Authorization: Basic {BasicCreds}
Content-Type: text/plain
Content-Length: 40

{Sha1}
```
```http
HTTP/1.1 201 Created
```

### 1.5 GET POM
```http
GET /maven/{Repo}/{GroupPath}/{ArtifactId}/{Version}/{ArtifactId}-{Version}.pom HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/xml

<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{GroupId}</groupId>
  <artifactId>{ArtifactId}</artifactId>
  <version>{Version}</version>
  <packaging>jar</packaging>
  <dependencies>
    <dependency>
      <groupId>{DepGroupId}</groupId>
      <artifactId>{DepArtifactId}</artifactId>
      <version>{DepVersion}</version>
    </dependency>
  </dependencies>
</project>
```

### 1.6 GET release version-list metadata
```http
GET /maven/{Repo}/{GroupPath}/{ArtifactId}/maven-metadata.xml HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/xml

<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>{GroupId}</groupId>
  <artifactId>{ArtifactId}</artifactId>
  <versioning>
    <latest>{Version}</latest>
    <release>{Version}</release>
    <versions>
      <version>{Version}</version>
    </versions>
    <lastUpdated>{LastUpdated}</lastUpdated>
  </versioning>
</metadata>
```

### 1.7 GET snapshot metadata (server-assigned `{BuildNumber}`, impl spec §5.2)
```http
GET /maven/{Repo}/{GroupPath}/{ArtifactId}/{BaseVersion}-SNAPSHOT/maven-metadata.xml HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/xml

<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>{GroupId}</groupId>
  <artifactId>{ArtifactId}</artifactId>
  <version>{BaseVersion}-SNAPSHOT</version>
  <versioning>
    <snapshot>
      <timestamp>{Timestamp}</timestamp>
      <buildNumber>{BuildNumber}</buildNumber>
    </snapshot>
    <lastUpdated>{LastUpdated}</lastUpdated>
    <snapshotVersions>
      <snapshotVersion>
        <extension>jar</extension>
        <value>{SnapshotVersion}</value>
        <updated>{LastUpdated}</updated>
      </snapshotVersion>
      <snapshotVersion>
        <extension>pom</extension>
        <value>{SnapshotVersion}</value>
        <updated>{LastUpdated}</updated>
      </snapshotVersion>
    </snapshotVersions>
  </versioning>
</metadata>
```

### 1.8 Immutable-release conflict
```http
PUT /maven/{Repo}/{GroupPath}/{ArtifactId}/{Version}/{FileName} HTTP/1.1
Authorization: Basic {BasicCreds}
...
```
```http
HTTP/1.1 409 Conflict
Content-Type: text/plain

Version {Version} already exists; releases are immutable
```

---

## 2. PyPI / pip

### 2.1 GET simple project index — HTML (PEP 503)
```http
GET /simple/{NormalizedName}/ HTTP/1.1
Host: {Host}
Accept: text/html
```
```http
HTTP/1.1 200 OK
Content-Type: text/html

<!DOCTYPE html>
<html>
  <body>
    <a href="/packages/{WheelName}#sha256={Sha256}" data-requires-python="{RequiresPython}">{WheelName}</a>
    <a href="/packages/{SdistName}#sha256={Sha256}" data-requires-python="{RequiresPython}">{SdistName}</a>
  </body>
</html>
```

### 2.2 GET simple project index — JSON (PEP 691)
```http
GET /simple/{NormalizedName}/ HTTP/1.1
Host: {Host}
Accept: application/vnd.pypi.simple.v1+json
```
```http
HTTP/1.1 200 OK
Content-Type: application/vnd.pypi.simple.v1+json

{
  "meta": { "api-version": "1.0" },
  "name": "{NormalizedName}",
  "files": [
    {
      "filename": "{WheelName}",
      "url": "/packages/{WheelName}",
      "hashes": { "sha256": "{Sha256}" },
      "requires-python": "{RequiresPython}",
      "yanked": false
    }
  ]
}
```

### 2.3 GET download
```http
GET /packages/{WheelName} HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Length: {Size}
ETag: "{Sha256}"

<binary wheel bytes>
```

### 2.4 POST upload (twine legacy multipart)
`{PypiBasic}` = base64 of `__token__:{Token}`.
```http
POST / HTTP/1.1
Host: {Host}
Authorization: Basic {PypiBasic}
Content-Type: multipart/form-data; boundary=----pypi-boundary

------pypi-boundary
Content-Disposition: form-data; name=":action"

file_upload
------pypi-boundary
Content-Disposition: form-data; name="protocol_version"

1
------pypi-boundary
Content-Disposition: form-data; name="name"

{Package}
------pypi-boundary
Content-Disposition: form-data; name="version"

{Version}
------pypi-boundary
Content-Disposition: form-data; name="filetype"

{FileType}
------pypi-boundary
Content-Disposition: form-data; name="pyversion"

{PyTag}
------pypi-boundary
Content-Disposition: form-data; name="metadata_version"

{MetadataVersion}
------pypi-boundary
Content-Disposition: form-data; name="sha256_digest"

{Sha256}
------pypi-boundary
Content-Disposition: form-data; name="content"; filename="{WheelName}"
Content-Type: application/octet-stream

<binary wheel bytes>
------pypi-boundary--
```
```http
HTTP/1.1 200 OK
```
Digest mismatch:
```http
HTTP/1.1 400 Bad Request
Content-Type: text/plain

sha256_digest does not match uploaded content
```

---

## 3. npm

Scoped names encode `/` as `%2f`: `{Package}` → `{EncodedPackage}`.

### 3.1 GET packument
```http
GET /{EncodedPackage} HTTP/1.1
Host: {Host}
Accept: application/json
```
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "_id": "{Package}",
  "name": "{Package}",
  "dist-tags": { "latest": "{Version}" },
  "versions": {
    "{Version}": {
      "name": "{Package}",
      "version": "{Version}",
      "dependencies": { "{DepName}": "{DepRange}" },
      "dist": {
        "tarball": "https://{Host}/{EncodedPackage}/-/{TarballName}",
        "shasum": "{Sha1}",
        "integrity": "{Integrity}"
      }
    }
  },
  "time": { "{Version}": "{IsoTime}" }
}
```

### 3.2 GET tarball
```http
GET /{EncodedPackage}/-/{TarballName} HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Length: {Size}
ETag: "{Sha256}"

<binary tgz bytes>
```

### 3.3 PUT publish (tarball base64-embedded in `_attachments`)
```http
PUT /{EncodedPackage} HTTP/1.1
Host: {Host}
Authorization: Bearer {Token}
Content-Type: application/json

{
  "_id": "{Package}",
  "name": "{Package}",
  "dist-tags": { "latest": "{Version}" },
  "versions": {
    "{Version}": {
      "name": "{Package}",
      "version": "{Version}",
      "dependencies": { "{DepName}": "{DepRange}" },
      "dist": { "shasum": "{Sha1}", "integrity": "{Integrity}" }
    }
  },
  "_attachments": {
    "{Package}-{Version}.tgz": {
      "content_type": "application/octet-stream",
      "data": "H4sIAAAAAAAA…<base64 tarball>",
      "length": {Size}
    }
  }
}
```
```http
HTTP/1.1 201 Created
Content-Type: application/json

{ "ok": true, "id": "{Package}", "rev": "{Rev}" }
```
Version already published:
```http
HTTP/1.1 409 Conflict
Content-Type: application/json

{ "error": "cannot modify pre-existing version: {Version}" }
```

### 3.4 dist-tags read / write
```http
GET /-/package/{EncodedPackage}/dist-tags HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "latest": "{Version}" }
```
Set a tag — the **request body is a bare JSON string** (quoted):
```http
PUT /-/package/{EncodedPackage}/dist-tags/{DistTag} HTTP/1.1
Host: {Host}
Authorization: Bearer {Token}
Content-Type: application/json

"{TagVersion}"
```
```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "latest": "{Version}", "{DistTag}": "{TagVersion}" }
```

---

## 4. NuGet V3

IDs and versions are lowercased in flat-container paths.

### 4.1 GET service index (resource discovery)
```http
GET /v3/index.json HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "version": "3.0.0",
  "resources": [
    { "@id": "https://{Host}/v3-flatcontainer/", "@type": "PackageBaseAddress/3.0.0" },
    { "@id": "https://{Host}/v3/registration/",  "@type": "RegistrationsBaseUrl/3.6.0" },
    { "@id": "https://{Host}/v3/query",          "@type": "SearchQueryService/3.5.0" },
    { "@id": "https://{Host}/v3/package",        "@type": "PackagePublish/2.0.0" }
  ]
}
```

### 4.2 GET flat-container version list
```http
GET /v3-flatcontainer/{LowerId}/index.json HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "versions": [ "{Version}" ] }
```

### 4.3 GET download nupkg
```http
GET /v3-flatcontainer/{LowerId}/{LowerVersion}/{NupkgName} HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Length: {Size}
ETag: "{Sha256}"

<binary nupkg bytes>
```

### 4.4 PUT push
```http
PUT /v3/package HTTP/1.1
Host: {Host}
X-NuGet-ApiKey: {ApiKey}
Content-Type: multipart/form-data; boundary=----nuget-boundary

------nuget-boundary
Content-Disposition: form-data; name="package"; filename="package.nupkg"
Content-Type: application/octet-stream

<binary nupkg bytes>
------nuget-boundary--
```
```http
HTTP/1.1 201 Created
```
Existing version:
```http
HTTP/1.1 409 Conflict
```

### 4.5 DELETE (unlist)
```http
DELETE /v3/package/{LowerId}/{LowerVersion} HTTP/1.1
Host: {Host}
X-NuGet-ApiKey: {ApiKey}
```
```http
HTTP/1.1 204 No Content
```

---

## 5. Cargo (sparse index)

Cargo sends the token in `Authorization` with **no scheme prefix**.

### 5.1 GET registry config
```http
GET /config.json HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "dl": "{DlBase}",
  "api": "{ApiBase}"
}
```

### 5.2 GET index file (NDJSON, one line per version)
`{IndexPath}` is derived from `{Crate}` length (≥4 chars → `{c0c1}/{c2c3}/{Crate}`).
```http
GET /index/{IndexPath} HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: text/plain

{"name":"{Crate}","vers":"{Version}","deps":[{"name":"{DepName}","req":"{DepReq}","features":[],"optional":false,"default_features":true,"target":null,"kind":"normal"}],"cksum":"{Cksum}","features":{},"yanked":false}
```
(In index lines the dependency version field is `req`. In the publish payload below it is `version_req` — the server translates between the two.)

### 5.3 GET download
```http
GET /api/v1/crates/{Crate}/{Version}/download HTTP/1.1
Host: {Host}
```
```http
HTTP/1.1 200 OK
Content-Type: application/octet-stream
Content-Length: {Size}

<binary .crate bytes>
```
(May instead answer `302 Found` with a `Location` pointing at the blob; cargo follows it.)

### 5.4 PUT publish (length-prefixed binary frame)
Body layout: `[u32-LE metadata length][metadata JSON][u32-LE crate length][.crate bytes]`.
```http
PUT /api/v1/crates/new HTTP/1.1
Host: {Host}
Authorization: {CargoToken}
Content-Type: application/octet-stream
Content-Length: {Size}

<4-byte LE length> {
  "name": "{Crate}",
  "vers": "{Version}",
  "deps": [
    {
      "name": "{DepName}",
      "version_req": "{DepReq}",
      "features": [],
      "optional": false,
      "default_features": true,
      "target": null,
      "kind": "normal"
    }
  ],
  "features": {},
  "authors": ["{Author}"],
  "description": "{Description}",
  "license": "{License}",
  "badges": {},
  "links": null
} <4-byte LE length> <binary .crate bytes>
```
```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "warnings": { "invalid_categories": [], "invalid_badges": [], "other": [] } }
```

### 5.5 DELETE yank / PUT unyank
```http
DELETE /api/v1/crates/{Crate}/{Version}/yank HTTP/1.1
Host: {Host}
Authorization: {CargoToken}
```
```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "ok": true }
```
(Yank flips `"yanked": true` on the matching index line; it does not delete bytes.)

---

## 6. Common error payloads

Applies across adapters; the `401` challenge scheme varies per ecosystem.

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Basic realm="{Realm}"
```
```http
HTTP/1.1 403 Forbidden
Content-Type: text/plain

Token not permitted to publish to this repository
```
```http
HTTP/1.1 404 Not Found
Content-Type: text/plain

No such package or version
```
```http
HTTP/1.1 409 Conflict
Content-Type: text/plain

Version {Version} already exists; releases are immutable
```
```http
HTTP/1.1 400 Bad Request
Content-Type: text/plain

Declared digest does not match uploaded content
```
```http
HTTP/1.1 413 Payload Too Large
Content-Type: text/plain

Artifact exceeds configured max upload size
```
