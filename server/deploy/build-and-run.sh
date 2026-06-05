#!/usr/bin/env bash
# build-and-run.sh — Build the frontend + server and run for manual/dev use.
# Run from the repo root: ./server/deploy/build-and-run.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${REPO_ROOT}/server/deploy/llm-web.env"

# ── Build frontend ─────────────────────────────────────────────────────────────
echo "[build-and-run] Building frontend..."
npm --prefix "${REPO_ROOT}/web" run build

# ── Build server ───────────────────────────────────────────────────────────────
echo "[build-and-run] Building server..."
"${REPO_ROOT}/gradlew" :server:installDist

# ── Load environment ───────────────────────────────────────────────────────────
if [[ -f "${ENV_FILE}" ]]; then
    echo "[build-and-run] Loading env from ${ENV_FILE}"
    set -a
    # shellcheck source=/dev/null
    source "${ENV_FILE}"
    set +a
else
    echo "[build-and-run] WARN: ${ENV_FILE} not found. Using env variables as-is."
fi

# ── Run server ─────────────────────────────────────────────────────────────────
SCRIPT="${REPO_ROOT}/server/build/install/server/bin/server"
echo "[build-and-run] Starting server on port ${KTOR_PORT:-8091}..."
exec "${SCRIPT}"
