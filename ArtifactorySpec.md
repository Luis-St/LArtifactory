# Artifact Server — Endpoint Payload Templates

Companion to the protocol spec (`artifact-server-spec.md`) and implementation spec
(`artifact-server-implementation-spec.md`). Wire-level request/response templates for
every endpoint. Every variable part is a `{NamedPattern}`.

Each exchange begins with a `#` comment legend giving an example value for every
variable it uses — those `#` lines are annotations, **not** part of the HTTP wire
format. §0 remains the master glossary (variable → meaning).

Conventions:
- Binary bodies shown as `<binary … bytes>`; truncated hex/base64 shown with `…`.
- Hostnames are illustrative subdomains; nginx terminates TLS in front (impl spec §9).

---

## 0. Placeholder glossary (meanings)

### Infrastructure
| Variable | Meaning |
|---|---|
| `{Host}` | server hostname |
| `{Repo}` | Maven repository name (`releases` / `snapshots`) |
| `{Realm}` | auth challenge realm |
| `{Size}` | `Content-Length` in bytes |

### Credentials
| Variable | Meaning |
|---|---|
| `{BasicCreds}` | base64 of `user:password` |
| `{PypiBasic}` | base64 of `__token__:{Token}` |
| `{Token}` | bearer token (npm) |
| `{ApiKey}` | NuGet API key |
| `{CargoToken}` | raw Cargo token (no scheme prefix) |

### Coordinates — Maven
| Variable | Meaning |
|---|---|
| `{GroupId}` / `{GroupPath}` | group dotted / slashed |
| `{ArtifactId}` | artifact id |
| `{Classifier}` | optional classifier |
| `{Ext}` | file extension (`jar`/`pom`/`module`) |
| `{FileName}` | `{ArtifactId}-{Version}[-{Classifier}].{Ext}` |

### Coordinates — package ecosystems
| Variable | Meaning |
|---|---|
| `{Package}` | package/library name (npm scoped = `@{Scope}/{Name}`) |
| `{Scope}` / `{Name}` | npm scope (no `@`) / bare name |
| `{EncodedPackage}` | npm name with `/` → `%2f` |
| `{NormalizedName}` | PyPI PEP 503 normalized name |
| `{PackageId}` / `{LowerId}` | NuGet id original / lowercased |
| `{Crate}` | Cargo crate name |
| `{IndexPath}` | Cargo length-based index path |

### Versions
| Variable | Meaning |
|---|---|
| `{Version}` / `{LowerVersion}` | release version / NuGet lowercased |
| `{BaseVersion}` | Maven snapshot base |
| `{Timestamp}` / `{BuildNumber}` | snapshot timestamp / build counter |
| `{SnapshotVersion}` | `{BaseVersion}-{Timestamp}-{BuildNumber}` |
| `{LastUpdated}` / `{IsoTime}` | metadata timestamp / ISO-8601 publish time |
| `{DistTag}` / `{TagVersion}` | npm tag name / its version |

### Files
| Variable | Meaning |
|---|---|
| `{WheelName}` / `{SdistName}` | PyPI wheel / sdist |
| `{TarballName}` | npm tarball |
| `{NupkgName}` | NuGet package (`{LowerId}.{LowerVersion}.nupkg`) |
| `{CrateFile}` | Cargo crate |

### Digests
| Variable | Meaning |
|---|---|
| `{Sha256}` / `{Sha1}` / `{Md5}` | content hashes |
| `{Integrity}` | npm SRI (sha512, base64) |
| `{Cksum}` | Cargo sha256 of the `.crate` |

### Dependencies & upload metadata
| Variable | Meaning |
|---|---|
| `{DepGroupId}` `{DepArtifactId}` `{DepVersion}` | Maven dependency |
| `{DepName}` `{DepRange}` | npm dependency |
| `{DepName}` `{DepReq}` | Cargo dependency |
| `{RequiresPython}` | PyPI python constraint |
| `{FileType}` `{PyTag}` `{MetadataVersion}` | PyPI upload fields |
| `{Rev}` | npm document revision |
| `{DlBase}` `{ApiBase}` | Cargo config URLs |
| `{Author}` `{Description}` `{License}` | Cargo publish metadata |

---

## 1. Maven / Gradle

### 1.1 GET artifact
```http
# {Host}       = maven.luis-st.net
# {Repo}       = releases
# {GroupPath}  = net/luisst
# {ArtifactId} = toolkit
# {Version}    = 1.0.7
# {FileName}   = toolkit-1.0.7.jar
# {Size}       = 48213
# {Sha256}     = e3b0c44298fc1c149afbf4c8996fb924…
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
# {Host}       = maven.luis-st.net
# {Repo}       = releases
# {GroupPath}  = net/luisst
# {ArtifactId} = toolkit
# {Version}    = 1.0.7
# {FileName}   = toolkit-1.0.7.jar
# {Size}       = 48213
# {Sha256}     = e3b0c44298fc1c…
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
# {Host}       = maven.luis-st.net
# {Repo}       = releases
# {GroupPath}  = net/luisst
# {ArtifactId} = toolkit
# {Version}    = 1.0.7
# {FileName}   = toolkit-1.0.7.jar
# {Size}       = 48213
# {Realm}      = maven-releases
# {BasicCreds} = Y2k6c2VjcmV0            (base64 of "ci:secret")
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
Retry with the credential:
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

### 1.4 PUT sibling checksum
```http
# {Host}       = maven.luis-st.net
# {Repo}       = releases
# {GroupPath}  = net/luisst
# {ArtifactId} = toolkit
# {Version}    = 1.0.7
# {FileName}   = toolkit-1.0.7.jar
# {BasicCreds} = Y2k6c2VjcmV0
# {Sha1}       = a94a8fe5ccb19ba61c4c0873d391e987982fbbd3
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
# {Host}           = maven.luis-st.net
# {Repo}           = releases
# {GroupPath}      = net/luisst
# {GroupId}        = net.luisst
# {ArtifactId}     = toolkit
# {Version}        = 1.0.7
# {DepGroupId}     = io.javalin
# {DepArtifactId}  = javalin
# {DepVersion}     = 6.1.3
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
# {Host}         = maven.luis-st.net
# {Repo}         = releases
# {GroupPath}    = net/luisst
# {GroupId}      = net.luisst
# {ArtifactId}   = toolkit
# {Version}      = 1.0.7
# {LastUpdated}  = 20260120143000
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
# {Host}             = maven.luis-st.net
# {Repo}             = snapshots
# {GroupPath}        = net/luisst
# {GroupId}          = net.luisst
# {ArtifactId}       = toolkit
# {BaseVersion}      = 1.1.0
# {Timestamp}        = 20260120.143000
# {BuildNumber}      = 7
# {SnapshotVersion}  = 1.1.0-20260120.143000-7
# {LastUpdated}      = 20260120143000
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
# {Host}       = maven.luis-st.net
# {Repo}       = releases
# {GroupPath}  = net/luisst
# {ArtifactId} = toolkit
# {Version}    = 1.0.7
# {FileName}   = toolkit-1.0.7.jar
# {BasicCreds} = Y2k6c2VjcmV0
PUT /maven/{Repo}/{GroupPath}/{ArtifactId}/{Version}/{FileName} HTTP/1.1
Host: {Host}
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
# {Host}             = pypi.luis-st.net
# {NormalizedName}   = luis-toolkit
# {WheelName}        = luis_toolkit-1.0.7-py3-none-any.whl
# {SdistName}        = luis_toolkit-1.0.7.tar.gz
# {Sha256}           = 9a1c…
# {RequiresPython}   = >=3.9
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
# {Host}             = pypi.luis-st.net
# {NormalizedName}   = luis-toolkit
# {WheelName}        = luis_toolkit-1.0.7-py3-none-any.whl
# {Sha256}           = 9a1c…
# {RequiresPython}   = >=3.9
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
# {Host}       = pypi.luis-st.net
# {WheelName}  = luis_toolkit-1.0.7-py3-none-any.whl
# {Size}       = 18422
# {Sha256}     = 9a1c…
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
```http
# {Host}             = pypi.luis-st.net
# {PypiBasic}        = X190b2tlbl9fOnB5cGkt…   (base64 of "__token__:{Token}")
# {Package}          = luis-toolkit
# {Version}          = 1.0.7
# {FileType}         = bdist_wheel
# {PyTag}            = py3
# {MetadataVersion}  = 2.1
# {Sha256}           = 9a1c…
# {WheelName}        = luis_toolkit-1.0.7-py3-none-any.whl
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

### 3.1 GET packument
```http
# {Host}            = npm.luis-st.net
# {Package}         = @luisst/toolkit
# {EncodedPackage}  = @luisst%2ftoolkit
# {Version}         = 1.0.7
# {TarballName}     = toolkit-1.0.7.tgz
# {DepName}         = lodash
# {DepRange}        = ^4.17.21
# {Sha1}            = a94a8fe5ccb19ba61c4c0873d391e987982fbbd3
# {Integrity}       = sha512-9a1c…==
# {IsoTime}         = 2026-01-20T14:30:00.000Z
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
# {Host}            = npm.luis-st.net
# {EncodedPackage}  = @luisst%2ftoolkit
# {TarballName}     = toolkit-1.0.7.tgz
# {Size}            = 20841
# {Sha256}          = 9a1c…
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
# {Host}            = npm.luis-st.net
# {Package}         = @luisst/toolkit
# {EncodedPackage}  = @luisst%2ftoolkit
# {Version}         = 1.0.7
# {DepName}         = lodash
# {DepRange}        = ^4.17.21
# {Sha1}            = a94a8fe…
# {Integrity}       = sha512-9a1c…==
# {Size}            = 20841
# {Token}           = npm_AbCdEf0123…
# {Rev}             = 3-a1b2c3
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
# {Host}            = npm.luis-st.net
# {EncodedPackage}  = @luisst%2ftoolkit
# {Version}         = 1.0.7
# {DistTag}         = beta
# {TagVersion}      = 1.1.0-beta.1
# {Token}           = npm_AbCdEf0123…
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

### 4.1 GET service index (resource discovery)
```http
# {Host}  = nuget.luis-st.net
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
# {Host}     = nuget.luis-st.net
# {LowerId}  = luisst.toolkit
# {Version}  = 1.0.7
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
# {Host}          = nuget.luis-st.net
# {LowerId}       = luisst.toolkit
# {LowerVersion}  = 1.0.7
# {NupkgName}     = luisst.toolkit.1.0.7.nupkg
# {Size}          = 51234
# {Sha256}        = b7d2…
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
# {Host}    = nuget.luis-st.net
# {ApiKey}  = oy2abc0123def…
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
# {Host}          = nuget.luis-st.net
# {LowerId}       = luisst.toolkit
# {LowerVersion}  = 1.0.7
# {ApiKey}        = oy2abc0123def…
DELETE /v3/package/{LowerId}/{LowerVersion} HTTP/1.1
Host: {Host}
X-NuGet-ApiKey: {ApiKey}
```
```http
HTTP/1.1 204 No Content
```

---

## 5. Cargo (sparse index)

### 5.1 GET registry config
```http
# {Host}     = cargo.luis-st.net
# {DlBase}   = https://cargo.luis-st.net/api/v1/crates
# {ApiBase}  = https://cargo.luis-st.net
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
```http
# {Host}       = cargo.luis-st.net
# {IndexPath}  = lu/is/luisst-toolkit     (≥4 chars → {c0c1}/{c2c3}/{Crate})
# {Crate}      = luisst-toolkit
# {Version}    = 1.0.7
# {DepName}    = serde
# {DepReq}     = ^1.0
# {Cksum}      = 9a1c…
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
# {Host}     = cargo.luis-st.net
# {Crate}    = luisst-toolkit
# {Version}  = 1.0.7
# {Size}     = 15903
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
```http
# {Host}         = cargo.luis-st.net
# {CargoToken}   = ci-token-abc123        (raw token, no scheme prefix)
# {Size}         = 16612
# {Crate}        = luisst-toolkit
# {Version}      = 1.0.7
# {DepName}      = serde
# {DepReq}       = ^1.0
# {Author}       = Luis
# {Description}  = Small toolkit crate
# {License}      = MIT
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
# {Host}        = cargo.luis-st.net
# {Crate}       = luisst-toolkit
# {Version}     = 1.0.7
# {CargoToken}  = ci-token-abc123
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

The `401` challenge scheme varies per ecosystem (Basic / Bearer / API-key).
```http
# {Realm}  = maven-releases
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
# {Version} = 1.0.7
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
