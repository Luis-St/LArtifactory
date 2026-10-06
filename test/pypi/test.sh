#!/usr/bin/env bash
# PyPI repository: build with 'build', upload with twine, install and list with pip.
# Requires: python3 (with venv), access to pypi.org to install build and twine

fresh_work_dir pypi
PYPI_DIR="$WORK_DIR/pypi"
REPO_URL="$ARTIFACTORY_URL/pypi/pypi-local"
SCHEME="${ARTIFACTORY_URL%%://*}"
HOST="${HOST_PORT%%:*}"
INDEX_URL="$SCHEME://lat-reader:$READER_TOKEN@$HOST_PORT/pypi/pypi-local/simple/"

check "create tool virtualenv with build and twine" bash -c "python3 -m venv '$PYPI_DIR/tools' && '$PYPI_DIR/tools/bin/pip' install -q build twine"
TWINE="$PYPI_DIR/tools/bin/twine"

# Upload
cd "$PYPI_DIR/package" || return
check "build 1.0.0 wheel and sdist" "$PYPI_DIR/tools/bin/python" -m build -q
check "twine uploads 1.0.0 with a token" "$TWINE" upload --non-interactive --repository-url "$REPO_URL/" -u __token__ -p "$CI_TOKEN" dist/*
check_fails "twine re-upload of 1.0.0 is rejected" "$TWINE" upload --non-interactive --repository-url "$REPO_URL/" -u __token__ -p "$CI_TOKEN" dist/*
check_fails "twine upload with a read only token is rejected" "$TWINE" upload --non-interactive --repository-url "$REPO_URL/" -u __token__ -p "$READER_TOKEN" dist/*

mv dist dist-1.0.0
sed -i 's/version = "1.0.0"/version = "1.1.0"/' pyproject.toml
check "build 1.1.0 wheel and sdist" "$PYPI_DIR/tools/bin/python" -m build -q
check "twine uploads 1.1.0 with username and password" "$TWINE" upload --non-interactive --repository-url "$REPO_URL/" -u lat-ci -p "$CI_PASSWORD" dist/*

WHEEL="$(ls dist/*.whl | head -n 1)"
check_status "upload with a wrong sha256 digest is rejected" 400 -u "__token__:$CI_TOKEN" -F ":action=file_upload" -F "name=LArtifactory_Test.Pkg" -F "version=9.9.9" \
	-F "sha256_digest=0000000000000000000000000000000000000000000000000000000000000000" -F "content=@$WHEEL;filename=lartifactory_test_pkg-9.9.9-py3-none-any.whl" "$REPO_URL/"

# Simple API
INDEX="$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/simple/")"
check_contains "project list contains the normalized name" "lartifactory-test-pkg" "$INDEX"
PAGE="$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/simple/lartifactory-test-pkg/")"
check_contains "project page links the 1.0.0 wheel" "lartifactory_test_pkg-1.0.0-py3-none-any.whl#sha256=" "$PAGE"
check_contains "project page links the 1.1.0 sdist" "lartifactory_test_pkg-1.1.0.tar.gz" "$PAGE"
check_contains "project page has requires-python" 'data-requires-python="&gt;=3.8"' "$PAGE"
JSON_PAGE="$(curl -sS -u "lat-reader:$READER_TOKEN" -H 'Accept: application/vnd.pypi.simple.v1+json' "$REPO_URL/simple/lartifactory-test-pkg/")"
check_equals "json project page lists 4 files" "4" "$(json_get "$JSON_PAGE" "len(data['files'])")"
check_equals "json project page lists versions" "['1.0.0', '1.1.0']" "$(json_get "$JSON_PAGE" "data['versions']")"
check_status "non normalized project name redirects" 301 -u "lat-reader:$READER_TOKEN" "$REPO_URL/simple/LArtifactory_Test.Pkg/"
check_status "unknown project returns 404" 404 -u "lat-reader:$READER_TOKEN" "$REPO_URL/simple/does-not-exist/"
check_status "anonymous index access is rejected" 401 "$REPO_URL/simple/lartifactory-test-pkg/"

# Install
check "create consumer virtualenv" python3 -m venv "$PYPI_DIR/consumer"
PIP_ARGS=(--disable-pip-version-check -q --index-url "$INDEX_URL" --trusted-host "$HOST")
check "pip installs lartifactory-test-pkg==1.0.0" "$PYPI_DIR/consumer/bin/pip" install "${PIP_ARGS[@]}" "lartifactory-test-pkg==1.0.0"
check_equals "installed 1.0.0 works" "hello from lartifactory-test-pkg 1.0.0" "$("$PYPI_DIR/consumer/bin/python" -c 'import lartifactory_test_pkg; print(lartifactory_test_pkg.hello())')"
check "pip upgrades to the latest version" "$PYPI_DIR/consumer/bin/pip" install "${PIP_ARGS[@]}" --upgrade "lartifactory-test-pkg"
check_equals "installed 1.1.0 works" "hello from lartifactory-test-pkg 1.1.0" "$("$PYPI_DIR/consumer/bin/python" -c 'import lartifactory_test_pkg; print(lartifactory_test_pkg.hello())')"
check_contains "pip lists the available versions" "1.1.0, 1.0.0" "$("$PYPI_DIR/consumer/bin/pip" index versions --disable-pip-version-check --index-url "$INDEX_URL" --trusted-host "$HOST" lartifactory-test-pkg 2>/dev/null)"
check "pip downloads the 1.0.0 wheel" "$PYPI_DIR/consumer/bin/pip" download "${PIP_ARGS[@]}" --only-binary :all: --no-deps -d "$PYPI_DIR/downloads" "lartifactory-test-pkg==1.0.0"
check "downloaded wheel matches the uploaded wheel" cmp "$PYPI_DIR/package/dist-1.0.0/lartifactory_test_pkg-1.0.0-py3-none-any.whl" "$PYPI_DIR/downloads/lartifactory_test_pkg-1.0.0-py3-none-any.whl"
check_fails "pip install without credentials fails" "$PYPI_DIR/consumer/bin/pip" install --disable-pip-version-check -q --no-input --index-url "$REPO_URL/simple/" --trusted-host "$HOST" --force-reinstall "lartifactory-test-pkg"

# List and delete
PACKAGES="$(curl -sS -u "lat-reader:$READER_TOKEN" "$ARTIFACTORY_URL/api/repositories/pypi-local/packages")"
check_contains "package api lists the project" '"name":"lartifactory-test-pkg"' "$PACKAGES"
check_status "admin deletes version 1.0.0" 204 -H "Authorization: Bearer $ADMIN_TOKEN" -X DELETE "$ARTIFACTORY_URL/api/repositories/pypi-local/packages?package=lartifactory-test-pkg&version=1.0.0"
check_not_contains "deleted version is gone from the index" "1.0.0" "$(curl -sS -u "lat-reader:$READER_TOKEN" "$REPO_URL/simple/lartifactory-test-pkg/")"

cd "$TEST_DIR" || return
