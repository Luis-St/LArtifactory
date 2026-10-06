#!/usr/bin/env bash
# Shared helpers for the repository tests, sourced by run.sh and the per type test scripts.

ARTIFACTORY_URL="${ARTIFACTORY_URL:-http://localhost:8080}"
ARTIFACTORY_URL="${ARTIFACTORY_URL%/}"
ADMIN_USER="${ARTIFACTORY_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ARTIFACTORY_ADMIN_PASSWORD:-admin-password}"
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$TEST_DIR/.work"
RESULTS_FILE="$WORK_DIR/results"
HOST_PORT="${ARTIFACTORY_URL#*://}"

RED=$'\033[31m'
GREEN=$'\033[32m'
BOLD=$'\033[1m'
RESET=$'\033[0m'

section() {
	echo
	echo "${BOLD}== $* ==${RESET}"
}

pass() {
	echo "  ${GREEN}PASS${RESET} $*"
	echo "PASS $*" >> "$RESULTS_FILE"
}

fail() {
	echo "  ${RED}FAIL${RESET} $*"
	echo "FAIL $*" >> "$RESULTS_FILE"
}

# check <description> <command...>: passes if the command succeeds
check() {
	local description="$1"
	shift
	local log="$WORK_DIR/last-command.log"
	if "$@" > "$log" 2>&1; then
		pass "$description"
	else
		fail "$description"
		sed 's/^/      /' "$log" | tail -n 25
	fi
}

# check_fails <description> <command...>: passes if the command fails
check_fails() {
	local description="$1"
	shift
	local log="$WORK_DIR/last-command.log"
	if "$@" > "$log" 2>&1; then
		fail "$description (command unexpectedly succeeded)"
		sed 's/^/      /' "$log" | tail -n 10
	else
		pass "$description"
	fi
}

# check_contains <description> <needle> <haystack>
check_contains() {
	if [[ "$3" == *"$2"* ]]; then
		pass "$1"
	else
		fail "$1 (expected to contain '$2')"
		echo "$3" | head -n 20 | sed 's/^/      /'
	fi
}

# check_not_contains <description> <needle> <haystack>
check_not_contains() {
	if [[ "$3" != *"$2"* ]]; then
		pass "$1"
	else
		fail "$1 (expected not to contain '$2')"
		echo "$3" | head -n 20 | sed 's/^/      /'
	fi
}

# check_equals <description> <expected> <actual>
check_equals() {
	if [[ "$2" == "$3" ]]; then
		pass "$1"
	else
		fail "$1 (expected '$2', got '$3')"
	fi
}

# check_status <description> <expected status> <curl args...>
check_status() {
	local description="$1"
	local expected="$2"
	shift 2
	local actual
	actual="$(curl -s -o /dev/null -w '%{http_code}' "$@")"
	check_equals "$description" "$expected" "$actual"
}

# api <curl args...>: calls the management api as administrator
api() {
	curl -sS -u "$ADMIN_USER:$ADMIN_PASSWORD" -H 'Content-Type: application/json' "$@"
}

# json_get <json> <python expression on 'data'>
json_get() {
	python3 -c "import json,sys; data=json.loads(sys.argv[1]); print($2)" "$1"
}

# fresh_work_dir <name>: recreates the work directory of a test from its template directory
fresh_work_dir() {
	local name="${1:?}"
	local target="${WORK_DIR:?}/$name"
	rm -rf -- "$target"
	mkdir -p "$target"
	cp -r "$TEST_DIR/$name/." "$target/"
	rm -f -- "$target/test.sh"
}
