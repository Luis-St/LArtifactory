#!/usr/bin/env bash
# Runs the dev build locally: starts the PostgreSQL container and the server via `./gradlew run`.
#
# Usage: ./run.sh [--ui] [--skip-ui-build] [--stop-db]
#   --ui             also start the Vite dev server (http://localhost:5173/ui/) with hot reload
#   --skip-ui-build  do not rebuild the bundled ui (uses the existing ui/dist)
#   --stop-db        stop the database container and exit
#
# The server listens on http://localhost:8080, admin login is admin / admin (see the run task in build.gradle.kts).
set -euo pipefail

cd "$(dirname "$0")"

COMPOSE=(docker compose -f docker-compose.db.yml)
DEV_UI=false
GRADLE_ARGS=()

# Prints the usage block from the header comment
usage() {
	sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
	case "$arg" in
		--ui) DEV_UI=true ;;
		--skip-ui-build) GRADLE_ARGS+=(-PskipUi) ;;
		--stop-db) "${COMPOSE[@]}" down; exit 0 ;;
		-h|--help) usage; exit 0 ;;
		*) echo "Unknown option: $arg" >&2; echo >&2; usage >&2; exit 1 ;;
	esac
done

echo "Starting the database..."
"${COMPOSE[@]}" up -d --wait

if $DEV_UI; then
	if [[ ! -d ui/node_modules ]]; then
		(cd ui && npm ci --no-audit --no-fund)
	fi
	(cd ui && npm run dev) &
	UI_PID=$!
	# Stop the Vite dev server together with the server
	trap 'kill "$UI_PID" 2>/dev/null || true' EXIT
fi

./gradlew run "${GRADLE_ARGS[@]}"
