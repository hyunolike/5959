#!/usr/bin/env bash
# 운영 DB를 pg_dump custom 포맷으로 떠서 R2에 올린다. 크론에서 매일 실행한다.
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/ogu}"
COMPOSE_FILE_PATH="${COMPOSE_FILE_PATH:-$DEPLOY_DIR/repo/infra/compose.prod.yaml}"

set -a
# shellcheck disable=SC1091
source "$DEPLOY_DIR/.env"
set +a

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="$(mktemp "/tmp/ogu-$STAMP.XXXX.dump")"
trap 'rm -f "$FILE"' EXIT

docker compose -f "$COMPOSE_FILE_PATH" --env-file "$DEPLOY_DIR/.env" exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$FILE"

rclone copyto "$FILE" "r2:$R2_BUCKET/postgres/ogu-$STAMP.dump"
echo "백업 완료: postgres/ogu-$STAMP.dump ($(du -h "$FILE" | cut -f1))"
