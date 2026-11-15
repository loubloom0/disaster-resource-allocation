#!/usr/bin/env bash
# Creates the role + database and runs every migration in db/migrations in order.
# Usage:  DB_PASSWORD='your-password' ./scripts/setup-db.sh
# Optional env: DB_NAME (default dras), DB_USER (default dras_app), PGHOST/PGPORT.
set -euo pipefail

: "${DB_PASSWORD:?Set DB_PASSWORD first, e.g.  export DB_PASSWORD='choose-a-password'}"
DB_NAME="${DB_NAME:-dras}"
DB_USER="${DB_USER:-dras_app}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Admin connection: the local 'postgres' superuser (override with PSQL_ADMIN if needed).
PSQL_ADMIN="${PSQL_ADMIN:-sudo -u postgres psql}"

echo ">> creating role '$DB_USER' (if missing) and database '$DB_NAME'"
# Keep the database password out of psql/sudo command-line arguments. The private
# temporary SQL file is removed on success, failure, or interruption.
role_sql="$(mktemp "${TMPDIR:-/tmp}/dras-role-setup.XXXXXX.sql")"
chmod 600 "$role_sql"
trap 'rm -f "$role_sql"' EXIT
escaped_password=${DB_PASSWORD//\'/\'\'}
cat >"$role_sql" <<SQL
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'u', '$escaped_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'u')
\gexec
SQL
$PSQL_ADMIN -v ON_ERROR_STOP=1 -v u="$DB_USER" -f "$role_sql"
rm -f "$role_sql"
trap - EXIT
$PSQL_ADMIN -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1 \
  || $PSQL_ADMIN -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_USER\""

echo ">> running migrations as '$DB_USER'"
run_sql() {
  PGPASSWORD="$DB_PASSWORD" psql -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" \
    -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q "$@"
}
# Tiny migration tracker so re-running this script never applies a file twice.
run_sql -c "CREATE TABLE IF NOT EXISTS schema_migrations (filename TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())"
for f in "$ROOT"/db/migrations/V*.sql; do
  name="$(basename "$f")"
  if [ -n "$(run_sql -tA -c "SELECT 1 FROM schema_migrations WHERE filename = '$name'")" ]; then
    echo "   $name (already applied, skipped)"
    continue
  fi
  echo "   $name"
  # --single-transaction: a migration either fully applies or leaves nothing behind.
  run_sql --single-transaction -f "$f" -c "INSERT INTO schema_migrations(filename) VALUES ('$name')"
done
echo ">> done"
