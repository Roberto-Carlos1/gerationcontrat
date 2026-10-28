#!/usr/bin/env bash
# Crée un cluster PostgreSQL éphémère, exécute les tests, puis le supprime.
# Aucun accès aux bases existantes. Ne pas lancer en root (restriction initdb).
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
pg_bin="${PG_BIN:-$(pg_config --bindir)}"
pg_test_port="${PACTE_TEST_PORT:-55432}"
pg_tmp="$(mktemp -d /tmp/pacte-pg-tests.XXXXXX)"
cleanup() {
  result=$?
  if ((result != 0)); then cat "$pg_tmp/postgres.log" 2>/dev/null || true; fi
  "$pg_bin/pg_ctl" -D "$pg_tmp/db" -m immediate stop >/dev/null 2>&1 || true
  rm -rf "$pg_tmp"
}
trap cleanup EXIT
"$pg_bin/initdb" -D "$pg_tmp/db" -U pacte -A trust --no-locale --encoding=UTF8 > "$pg_tmp/init.log"
"$pg_bin/pg_ctl" -D "$pg_tmp/db" -l "$pg_tmp/postgres.log" -o "-h 127.0.0.1 -k $pg_tmp -p $pg_test_port" start
"$pg_bin/createdb" -h 127.0.0.1 -p "$pg_test_port" -U pacte pacte_test
export PACTE_TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:$pg_test_port/pacte_test"
export PACTE_TEST_DATABASE_USER=pacte
export PACTE_TEST_DATABASE_PASSWORD=''
mvn -B -f "$project_root/backend/pom.xml" verify "$@"
