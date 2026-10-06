#!/usr/bin/env bash
# Cargo registry (sparse index): publish, yank, search and build with cargo.
# Requires: cargo

fresh_work_dir cargo
CARGO_DIR="$WORK_DIR/cargo"
REGISTRY="$ARTIFACTORY_URL/cargo/cargo-local"
export CARGO_HOME="$CARGO_DIR/home"
export CARGO_TARGET_DIR="$CARGO_DIR/target"
mkdir -p "$CARGO_DIR/.cargo"
cat > "$CARGO_DIR/.cargo/config.toml" <<EOF
[registries.lartifactory]
index = "sparse+$REGISTRY/index/"

[registry]
global-credential-providers = ["cargo:token"]
EOF

cargo_as() {
	local token="$1"
	shift
	CARGO_REGISTRIES_LARTIFACTORY_TOKEN="$token" cargo "$@"
}

# Publish
cd "$CARGO_DIR/test-crate" || return
check "cargo publishes lartifactory-test-crate 0.1.0" cargo_as "$CI_TOKEN" publish --registry lartifactory --allow-dirty
check_fails "cargo re-publish of 0.1.0 is rejected" cargo_as "$CI_TOKEN" publish --registry lartifactory --allow-dirty --no-verify
check_contains "re-publish error is reported by cargo" "already exists" "$(cargo_as "$CI_TOKEN" publish --registry lartifactory --allow-dirty --no-verify 2>&1)"
python3 -c 'import json,struct,sys; meta=json.dumps({"name":"lartifactory-test-crate","vers":"0.1.0","deps":[],"features":{}}).encode(); sys.stdout.buffer.write(struct.pack("<I",len(meta))+meta+struct.pack("<I",4)+b"test")' > "$CARGO_DIR/publish.bin"
check_status "server rejects a duplicate version with 409" 409 -X PUT -H "Authorization: $CI_TOKEN" --data-binary "@$CARGO_DIR/publish.bin" "$REGISTRY/api/v1/crates/new"
sed -i 's/version = "0.1.0"/version = "0.2.0"/' Cargo.toml
check_fails "cargo publish with a read only token is rejected" cargo_as "$READER_TOKEN" publish --registry lartifactory --allow-dirty --no-verify
cd "$CARGO_DIR/test-dep" || return
check "cargo publishes lartifactory-test-dep with a renamed registry dependency" cargo_as "$CI_TOKEN" publish --registry lartifactory --allow-dirty

# Index
AUTH=(-H "Authorization: $READER_TOKEN")
check_status "anonymous config.json access is rejected" 401 "$REGISTRY/index/config.json"
CONFIG="$(curl -sS "${AUTH[@]}" "$REGISTRY/index/config.json")"
check_equals "config.json has the download url" "$REGISTRY/api/v1/crates" "$(json_get "$CONFIG" "data['dl']")"
check_equals "config.json requires auth for private repositories" "True" "$(json_get "$CONFIG" "data['auth-required']")"
INDEX_LINE="$(curl -sS "${AUTH[@]}" "$REGISTRY/index/la/rt/lartifactory-test-crate")"
check_equals "index line has the version" "0.1.0" "$(json_get "$INDEX_LINE" "data['vers']")"
check_equals "index line has the features" "{'extra': []}" "$(json_get "$INDEX_LINE" "data['features']")"
CRATE_SHA="$(curl -sS "${AUTH[@]}" "$REGISTRY/api/v1/crates/lartifactory-test-crate/0.1.0/download" | sha256sum | cut -d' ' -f1)"
check_equals "downloaded crate matches the index checksum" "$(json_get "$INDEX_LINE" "data['cksum']")" "$CRATE_SHA"
DEP_LINE="$(curl -sS "${AUTH[@]}" "$REGISTRY/index/la/rt/lartifactory-test-dep")"
check_equals "renamed dependency uses the name from the manifest" "base" "$(json_get "$DEP_LINE" "data['deps'][0]['name']")"
check_equals "renamed dependency references the package" "lartifactory-test-crate" "$(json_get "$DEP_LINE" "data['deps'][0]['package']")"
check_equals "dependency requirement is translated to req" "^0.1" "$(json_get "$DEP_LINE" "data['deps'][0]['req']")"
check_status "index path must match the crate name" 404 "${AUTH[@]}" "$REGISTRY/index/xx/yy/lartifactory-test-crate"

# Build
cd "$CARGO_DIR/consumer" || return
check_equals "consumer builds against the registry and runs" "hello from lartifactory-test-crate (extra) via lartifactory-test-dep" \
	"$(cargo_as "$READER_TOKEN" run -q 2>&1 | tail -n 1)"
check_contains "lockfile references the registry" "sparse+$REGISTRY/index/" "$(cat Cargo.lock)"

# Search, owners and yank
check_contains "cargo search lists the crates" "lartifactory-test-dep = \"0.1.0\"" "$(cargo_as "$READER_TOKEN" search --registry lartifactory lartifactory 2>/dev/null)"
check_contains "cargo owner lists the publisher" "lat-ci" "$(cargo_as "$READER_TOKEN" owner --list --registry lartifactory lartifactory-test-crate 2>/dev/null)"
check_fails "reader can not yank" cargo_as "$READER_TOKEN" yank --registry lartifactory --version 0.1.0 lartifactory-test-dep
check "cargo yank 0.1.0" cargo_as "$CI_TOKEN" yank --registry lartifactory --version 0.1.0 lartifactory-test-dep
check_equals "yanked version is marked in the index" "True" "$(json_get "$(curl -sS "${AUTH[@]}" "$REGISTRY/index/la/rt/lartifactory-test-dep")" "data['yanked']")"
check "cargo yank --undo 0.1.0" cargo_as "$CI_TOKEN" yank --undo --registry lartifactory --version 0.1.0 lartifactory-test-dep
check_equals "unyanked version is restored in the index" "False" "$(json_get "$(curl -sS "${AUTH[@]}" "$REGISTRY/index/la/rt/lartifactory-test-dep")" "data['yanked']")"

PACKAGES="$(curl -sS -u "lat-reader:$READER_TOKEN" "$ARTIFACTORY_URL/api/repositories/cargo-local/packages")"
check_contains "package api lists the crates" '"name":"lartifactory-test-dep"' "$PACKAGES"

cd "$TEST_DIR" || return
