#!/usr/bin/env bash
# 사용법: deploy.sh <이미지 태그>
# 새 태그로 api 컨테이너를 교체하고, 헬스 체크에 실패하면 이전 태그로 되돌린 뒤 실패로 끝낸다.
set -euo pipefail

NEW_TAG="${1:?이미지 태그가 필요합니다}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/ogu}"
COMPOSE_FILE_PATH="${COMPOSE_FILE_PATH:-$DEPLOY_DIR/repo/infra/compose.prod.yaml}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
HEALTH_RETRIES="${HEALTH_RETRIES:-30}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-2}"
ENV_FILE="$DEPLOY_DIR/.env"

current_tag() { grep -E '^API_TAG=' "$ENV_FILE" | cut -d= -f2; }

set_tag() {
  sed -i.bak "s/^API_TAG=.*/API_TAG=$1/" "$ENV_FILE"
  rm -f "$ENV_FILE.bak"
}

up_api() {
  docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" up -d --pull always api
  # 첫 배포에서 Caddy를 띄운다. 이미 떠 있으면 아무것도 하지 않는다(이미지를 새로 받지 않는다)
  docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" up -d caddy
}

wait_healthy() {
  local i
  for ((i = 1; i <= HEALTH_RETRIES; i++)); do
    if curl -fsS "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep "$HEALTH_INTERVAL"
  done
  return 1
}

PREV_TAG="$(current_tag)"
set_tag "$NEW_TAG"
up_api

if wait_healthy; then
  echo "배포 성공: $NEW_TAG"
  exit 0
fi

echo "헬스 체크 실패: $NEW_TAG, $PREV_TAG 로 롤백합니다" >&2
set_tag "$PREV_TAG"
up_api
if ! wait_healthy; then
  echo "롤백한 $PREV_TAG 도 헬스 체크에 실패했습니다" >&2
fi
exit 1
