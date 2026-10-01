#!/usr/bin/env bash
# start_db.sh - Start the local TalentMatch AI PostgreSQL container and apply
# Flyway migrations from src/main/resources/db/migration.
#
# Usage: bash scripts/start_db.sh
#
# Configuration: optional scripts/.env (see scripts/.env.example).
# Notes:
#   - POSTGRES_* settings (DB_NAME, DB_USER, DB_PASSWORD) only apply on the
#     FIRST initialization of the data volume. To reset:
#       docker rm -f talentmatch-postgres && docker volume rm talentmatch-pgdata
#   - If host port 5432 is taken (e.g. by a Windows PostgreSQL install),
#     set DB_PORT=5433 in scripts/.env.
#   - Idempotent: safe to run repeatedly.
set -euo pipefail

log()  { printf '[start_db] %s\n' "$*"; }
fail() { printf '[start_db] ERROR: %s\n' "$*" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MIGRATION_DIR="$REPO_ROOT/src/main/resources/db/migration"

[[ -d "$MIGRATION_DIR" ]] || fail "Migration directory not found: $MIGRATION_DIR"

if [[ -f "$SCRIPT_DIR/.env" ]]; then
    log "Loading $SCRIPT_DIR/.env"
    set -a
    # shellcheck disable=SC1091
    source "$SCRIPT_DIR/.env"
    set +a
fi

DB_CONTAINER="${DB_CONTAINER:-talentmatch-postgres}"
DB_VOLUME="${DB_VOLUME:-talentmatch-pgdata}"
DB_NETWORK="${DB_NETWORK:-talentmatch-net}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-talentmatch}"
DB_USER="${DB_USER:-talentmatch}"
DB_PASSWORD="${DB_PASSWORD:-talentmatch}"   # local dev only
PG_IMAGE="${PG_IMAGE:-postgres:16}"
FLYWAY_IMAGE="${FLYWAY_IMAGE:-flyway/flyway:10}"
DB_READY_TIMEOUT="${DB_READY_TIMEOUT:-60}"

# --- Preflight -------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "docker CLI not found on PATH"
docker info >/dev/null 2>&1 \
    || fail "Docker daemon not reachable (is Docker Desktop / dockerd running in WSL?)"

# --- 1. Network ------------------------------------------------------------
if docker network inspect "$DB_NETWORK" >/dev/null 2>&1; then
    log "Network $DB_NETWORK exists"
else
    log "Creating network $DB_NETWORK"
    docker network create "$DB_NETWORK" >/dev/null
fi

# --- 2. Volume -------------------------------------------------------------
docker volume create "$DB_VOLUME" >/dev/null

# --- 3. Container ----------------------------------------------------------
running="$(docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null || true)"
case "$running" in
    true)
        log "Container $DB_CONTAINER already running"
        docker network connect "$DB_NETWORK" "$DB_CONTAINER" 2>/dev/null || true
        ;;
    false)
        log "Starting existing container $DB_CONTAINER"
        docker network connect "$DB_NETWORK" "$DB_CONTAINER" 2>/dev/null || true
        docker start "$DB_CONTAINER" >/dev/null
        ;;
    *)
        log "Creating container $DB_CONTAINER ($PG_IMAGE) on host port $DB_PORT"
        docker run -d \
            --name "$DB_CONTAINER" \
            --network "$DB_NETWORK" \
            -e POSTGRES_DB="$DB_NAME" \
            -e POSTGRES_USER="$DB_USER" \
            -e POSTGRES_PASSWORD="$DB_PASSWORD" \
            -p "$DB_PORT:5432" \
            -v "$DB_VOLUME:/var/lib/postgresql/data" \
            --restart unless-stopped \
            "$PG_IMAGE" >/dev/null
        ;;
esac

# --- 4. Readiness ----------------------------------------------------------
# Use TCP via 127.0.0.1 (not the unix socket): the first-boot init server
# listens on the socket only, so a socket check can report ready too early.
log "Waiting for PostgreSQL (timeout ${DB_READY_TIMEOUT}s)"
elapsed=0
until docker exec "$DB_CONTAINER" pg_isready -h 127.0.0.1 -p 5432 \
        -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; do
    if (( elapsed >= DB_READY_TIMEOUT )); then
        printf '[start_db] ERROR: PostgreSQL not ready after %ss. Last logs:\n' "$DB_READY_TIMEOUT" >&2
        docker logs --tail 50 "$DB_CONTAINER" >&2 || true
        exit 1
    fi
    sleep 1
    elapsed=$((elapsed + 1))
done
log "PostgreSQL is ready"

# --- 5. Flyway migrations --------------------------------------------------
log "Applying migrations from $MIGRATION_DIR"
docker run --rm \
    --network "$DB_NETWORK" \
    -v "$MIGRATION_DIR:/flyway/sql:ro" \
    -e FLYWAY_URL="jdbc:postgresql://$DB_CONTAINER:5432/$DB_NAME" \
    -e FLYWAY_USER="$DB_USER" \
    -e FLYWAY_PASSWORD="$DB_PASSWORD" \
    -e FLYWAY_CONNECT_RETRIES=10 \
    "$FLYWAY_IMAGE" migrate

# --- 6. Done ---------------------------------------------------------------
log "Database ready: postgresql://$DB_USER:***@localhost:$DB_PORT/$DB_NAME"
log "Connect with: docker exec -it $DB_CONTAINER psql -U $DB_USER -d $DB_NAME"
