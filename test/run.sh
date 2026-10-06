#!/usr/bin/env bash
# Runs the repository tests against a running LArtifactory server.
#
#   ARTIFACTORY_URL=http://localhost:8080 ARTIFACTORY_ADMIN_PASSWORD=... ./run.sh [generic] [maven] [pypi] [npm] [nuget] [cargo]
#
# For every type a repository is (re)created, packages are uploaded with the native client,
# then downloaded, listed and installed again. Users 'lat-ci' (write) and 'lat-reader' (read) are created for the tests.
set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

TYPES=("$@")
if [[ ${#TYPES[@]} -eq 0 ]]; then
	TYPES=(generic maven pypi npm nuget cargo)
fi

mkdir -p "$WORK_DIR"
: > "$RESULTS_FILE"

section "setup"
if ! curl -sf "$ARTIFACTORY_URL/health" > /dev/null; then
	echo "LArtifactory is not reachable at $ARTIFACTORY_URL"
	exit 1
fi
check_status "admin credentials are valid" 200 -u "$ADMIN_USER:$ADMIN_PASSWORD" "$ARTIFACTORY_URL/api/me"

declare -A REPOS=([generic]=generic-local [maven]=maven-releases [pypi]=pypi-local [npm]=npm-local [nuget]=nuget-local [cargo]=cargo-local)
for type in "${TYPES[@]}"; do
	[[ -n "${REPOS[$type]:-}" ]] || continue
	api -o /dev/null -X DELETE "$ARTIFACTORY_URL/api/repositories/${REPOS[$type]}"
	check_status "create $type repository ${REPOS[$type]}" 201 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
		-X POST "$ARTIFACTORY_URL/api/repositories" -d "{\"name\":\"${REPOS[$type]}\",\"type\":\"$type\",\"description\":\"test repository\"}"
done
api -o /dev/null -X DELETE "$ARTIFACTORY_URL/api/repositories/maven-snapshots"
check_status "create maven repository maven-snapshots" 201 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
	-X POST "$ARTIFACTORY_URL/api/repositories" -d '{"name":"maven-snapshots","type":"maven"}'
check_status "creating a duplicate repository conflicts" 409 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
	-X POST "$ARTIFACTORY_URL/api/repositories" -d '{"name":"maven-snapshots","type":"maven"}'
check_status "creating a repository with an unknown type fails" 400 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
	-X POST "$ARTIFACTORY_URL/api/repositories" -d '{"name":"unknown-type","type":"docker"}'

CI_PASSWORD="ci-password-$RANDOM$RANDOM"
READER_PASSWORD="reader-password-$RANDOM$RANDOM"
for user in lat-ci lat-reader; do
	api -o /dev/null -X DELETE "$ARTIFACTORY_URL/api/users/$user"
done
check_status "create user lat-ci" 201 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
	-X POST "$ARTIFACTORY_URL/api/users" -d "{\"username\":\"lat-ci\",\"password\":\"$CI_PASSWORD\"}"
check_status "create user lat-reader" 201 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
	-X POST "$ARTIFACTORY_URL/api/users" -d "{\"username\":\"lat-reader\",\"password\":\"$READER_PASSWORD\"}"
check_status "grant lat-ci write on all repositories" 200 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
	-X PUT "$ARTIFACTORY_URL/api/permissions" -d '{"repository":"*","username":"lat-ci","level":"WRITE"}'
check_status "grant lat-reader read on all repositories" 200 -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' \
	-X PUT "$ARTIFACTORY_URL/api/permissions" -d '{"repository":"*","username":"lat-reader","level":"READ"}'

CI_TOKEN="$(json_get "$(curl -sS -u "lat-ci:$CI_PASSWORD" -H 'Content-Type: application/json' -X POST "$ARTIFACTORY_URL/api/tokens" -d '{"name":"tests"}')" "data['token']")"
READER_TOKEN="$(json_get "$(curl -sS -u "lat-reader:$READER_PASSWORD" -H 'Content-Type: application/json' -X POST "$ARTIFACTORY_URL/api/tokens" -d '{"name":"tests"}')" "data['token']")"
ADMIN_TOKEN="$(json_get "$(api -X POST "$ARTIFACTORY_URL/api/tokens" -d '{"name":"tests"}')" "data['token']")"
check_contains "lat-ci token created" "lat_" "$CI_TOKEN"
check_contains "lat-reader token created" "lat_" "$READER_TOKEN"
check_status "token works as bearer token" 200 -H "Authorization: Bearer $CI_TOKEN" "$ARTIFACTORY_URL/api/me"
check_status "invalid token is rejected" 401 -H "Authorization: Bearer lat_invalid" "$ARTIFACTORY_URL/api/me"
check_status "wrong password is rejected" 401 -u "lat-ci:wrong-password" "$ARTIFACTORY_URL/api/me"
check_status "non admin can not create repositories" 403 -u "lat-ci:$CI_PASSWORD" -H 'Content-Type: application/json' \
	-X POST "$ARTIFACTORY_URL/api/repositories" -d '{"name":"forbidden","type":"maven"}'

export ARTIFACTORY_URL HOST_PORT CI_PASSWORD READER_PASSWORD CI_TOKEN READER_TOKEN ADMIN_TOKEN

for type in "${TYPES[@]}"; do
	section "$type"
	if [[ ! -f "$TEST_DIR/$type/test.sh" ]]; then
		fail "unknown test type $type"
		continue
	fi
	source "$TEST_DIR/$type/test.sh"
done

section "summary"
PASSED="$(grep -c '^PASS' "$RESULTS_FILE")"
FAILED="$(grep -c '^FAIL' "$RESULTS_FILE")"
echo "  $PASSED passed, $FAILED failed"
if [[ "$FAILED" -gt 0 ]]; then
	grep '^FAIL' "$RESULTS_FILE" | sed 's/^/  /'
	exit 1
fi
