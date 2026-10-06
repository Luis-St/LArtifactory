#!/usr/bin/env bash
# npm repository: publish, dist-tags, deprecate, unpublish, view and install with npm.
# Requires: node, npm

fresh_work_dir npm
NPM_DIR="$WORK_DIR/npm"
REGISTRY="$ARTIFACTORY_URL/npm/npm-local/"
PACKAGE="@lartifactory/test-lib"

# One user config per identity, isolated from ~/.npmrc
write_npmrc() {
	printf 'registry=%s\n//%s/npm/npm-local/:_authToken=%s\n' "$REGISTRY" "$HOST_PORT" "$2" > "$NPM_DIR/$1.npmrc"
}
write_npmrc ci "$CI_TOKEN"
write_npmrc reader "$READER_TOKEN"
write_npmrc admin "$ADMIN_TOKEN"
printf 'registry=%s\n' "$REGISTRY" > "$NPM_DIR/anonymous.npmrc"
npm_as() {
	local identity="$1"
	shift
	npm --userconfig "$NPM_DIR/$identity.npmrc" --cache "$NPM_DIR/cache" --no-audit --no-fund --loglevel error "$@"
}

# Publish
cd "$NPM_DIR/package" || return
check "npm publishes $PACKAGE@1.0.0" npm_as ci publish
check_fails "npm re-publish of 1.0.0 is rejected" npm_as ci publish
check_contains "re-publish error names the existing version" "cannot modify pre-existing version: 1.0.0" "$(npm_as ci publish 2>&1)"
check_fails "npm publish with a read only token is rejected" npm_as reader publish --tag other
check_fails "npm publish without a token is rejected" npm_as anonymous publish --tag other
sed -i 's/"version": "1.0.0"/"version": "1.1.0-beta.1"/' package.json
check "npm publishes 1.1.0-beta.1 with tag beta" npm_as ci publish --tag beta

# Read
check_equals "npm whoami returns the token owner" "lat-ci" "$(npm_as ci whoami 2>/dev/null)"
check_equals "npm view lists both versions" '["1.0.0","1.1.0-beta.1"]' "$(npm_as reader view "$PACKAGE" versions --json 2>/dev/null | tr -d ' \n')"
check_equals "latest tag points to 1.0.0" "1.0.0" "$(npm_as reader view "$PACKAGE" dist-tags.latest 2>/dev/null)"
check_equals "beta tag points to 1.1.0-beta.1" "1.1.0-beta.1" "$(npm_as reader view "$PACKAGE" dist-tags.beta 2>/dev/null)"
check_status "packument with encoded scope separator" 200 -H "Authorization: Bearer $READER_TOKEN" "$REGISTRY@lartifactory%2ftest-lib"
check_status "packument with plain scope separator" 200 -H "Authorization: Bearer $READER_TOKEN" "$REGISTRY@lartifactory/test-lib"
check_status "anonymous packument access is rejected" 401 "$REGISTRY@lartifactory%2ftest-lib"
check_status "unknown package returns 404" 404 -H "Authorization: Bearer $READER_TOKEN" "$REGISTRY@lartifactory%2fmissing"
TARBALL_URL="$(npm_as reader view "$PACKAGE@1.0.0" dist.tarball 2>/dev/null)"
SHASUM="$(npm_as reader view "$PACKAGE@1.0.0" dist.shasum 2>/dev/null)"
check_equals "tarball url points to the repository" "${REGISTRY}@lartifactory/test-lib/-/test-lib-1.0.0.tgz" "$TARBALL_URL"
check_equals "tarball matches its shasum" "$SHASUM" "$(curl -sS -H "Authorization: Bearer $READER_TOKEN" "$TARBALL_URL" | sha1sum | cut -d' ' -f1)"
check_contains "npm search finds the package" "$PACKAGE" "$(npm_as reader search lartifactory 2>/dev/null)"

# dist-tags
check "npm dist-tag add" npm_as ci dist-tag add "$PACKAGE@1.1.0-beta.1" next
check_contains "npm dist-tag ls shows the new tag" "next: 1.1.0-beta.1" "$(npm_as reader dist-tag ls "$PACKAGE" 2>/dev/null)"
check "npm dist-tag rm" npm_as ci dist-tag rm "$PACKAGE" next
check_not_contains "removed tag is gone" "next" "$(npm_as reader dist-tag ls "$PACKAGE" 2>/dev/null)"
check_fails "reader can not add tags" npm_as reader dist-tag add "$PACKAGE@1.0.0" stable

# Install
cd "$NPM_DIR/consumer" || return
check "npm install resolves $PACKAGE from the registry" npm_as reader install
check_equals "installed package works" "hello from test-lib 1.0.0" "$(node index.js 2>&1)"
check_contains "lockfile references the registry tarball" "$REGISTRY@lartifactory/test-lib/-/test-lib-1.0.0.tgz" "$(cat package-lock.json)"
rm -rf -- "${NPM_DIR:?}/consumer/node_modules"
check_fails "npm install without credentials fails" npm_as anonymous install --prefer-online --cache "$NPM_DIR/empty-cache"

# Deprecate and unpublish
cd "$NPM_DIR/package" || return
check "npm deprecate 1.0.0" npm_as ci deprecate "$PACKAGE@1.0.0" "use 1.1.0"
check_equals "deprecation message is visible" "use 1.1.0" "$(npm_as reader view "$PACKAGE@1.0.0" deprecated 2>/dev/null)"
check_fails "writer can not unpublish" npm_as ci unpublish "$PACKAGE@1.1.0-beta.1"
check "admin unpublishes 1.1.0-beta.1" npm_as admin unpublish "$PACKAGE@1.1.0-beta.1"
check_equals "unpublished version is gone" "1.0.0" "$(npm_as reader view "$PACKAGE" versions --json 2>/dev/null | tr -d ' \n[]"')"
check_not_contains "tag of the unpublished version is gone" "beta" "$(npm_as reader dist-tag ls "$PACKAGE" 2>/dev/null)"

# Legacy login (npm login --auth-type=legacy)
LOGIN="$(curl -sS -X PUT -H 'Content-Type: application/json' -d "{\"name\":\"lat-ci\",\"password\":\"$CI_PASSWORD\"}" "$REGISTRY-/user/org.couchdb.user:lat-ci")"
check_contains "legacy login returns a token" '"token":"lat_' "$LOGIN"
check_status "legacy login with a wrong password is rejected" 401 -X PUT -H 'Content-Type: application/json' -d '{"name":"lat-ci","password":"wrong"}' "$REGISTRY-/user/org.couchdb.user:lat-ci"

check "admin unpublishes the whole package" npm_as admin unpublish "$PACKAGE" --force
check_status "unpublished package is gone" 404 -H "Authorization: Bearer $READER_TOKEN" "$REGISTRY@lartifactory%2ftest-lib"

cd "$TEST_DIR" || return
