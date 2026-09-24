#!/usr/bin/env bash
# deploy.sh를 docker, curl 스텁으로 실행해 성공과 롤백 경로를 검증한다.
# assert의 조건식은 eval로 나중에 평가하므로 작은따옴표로 넘긴다.
# shellcheck disable=SC2016
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="$ROOT/scripts/deploy.sh"
failures=0

setup() {
  WORK="$(mktemp -d)"
  mkdir -p "$WORK/bin"
  printf 'API_TAG=v1\nPOSTGRES_DB=ogu\n' > "$WORK/.env"
  touch "$WORK/compose.prod.yaml"

  # docker 스텁: 호출 기록만 남긴다
  cat > "$WORK/bin/docker" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$WORK/docker.log"
EOF
  # curl 스텁: 현재 .env의 태그가 bad면 실패, 아니면 UP
  cat > "$WORK/bin/curl" <<EOF
#!/usr/bin/env bash
if grep -q '^API_TAG=bad' "$WORK/.env"; then exit 22; fi
echo '{"status":"UP"}'
EOF
  chmod +x "$WORK/bin/docker" "$WORK/bin/curl"
}

# API_TAG가 없는 .env: current_tag()가 조기에 실패해야 한다(docker는 절대 호출되지 않아야 한다)
setup_no_tag() {
  WORK="$(mktemp -d)"
  mkdir -p "$WORK/bin"
  printf 'POSTGRES_DB=ogu\n' > "$WORK/.env"
  touch "$WORK/compose.prod.yaml"

  cat > "$WORK/bin/docker" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "$WORK/docker.log"
EOF
  cat > "$WORK/bin/curl" <<EOF
#!/usr/bin/env bash
echo '{"status":"UP"}'
EOF
  chmod +x "$WORK/bin/docker" "$WORK/bin/curl"
}

run_deploy() {
  PATH="$WORK/bin:$PATH" DEPLOY_DIR="$WORK" COMPOSE_FILE_PATH="$WORK/compose.prod.yaml" \
    HEALTH_RETRIES=2 HEALTH_INTERVAL=0 bash "$DEPLOY" "$1" > "$WORK/out.log" 2>&1
}

assert() {
  if eval "$2"; then echo "PASS $1"; else echo "FAIL $1"; cat "$WORK/out.log"; failures=$((failures + 1)); fi
}

setup
run_deploy v2 && status=0 || status=$?
assert "US3-AC1 헬스 체크를 통과하면 새 태그로 배포한다" \
  '[[ $status -eq 0 ]] && grep -q "^API_TAG=v2$" "$WORK/.env"'

setup
# status는 뒤따르는 assert의 eval 문자열 안에서 참조되므로 shellcheck는 사용처를 보지 못한다
# shellcheck disable=SC2034
run_deploy bad && status=0 || status=$?
assert "US3-AC2 헬스 체크에 실패하면 이전 태그로 롤백하고 실패한다" \
  '[[ $status -eq 1 ]] && grep -q "^API_TAG=v1$" "$WORK/.env" && [[ $(grep -c "up -d --pull always api" "$WORK/docker.log") -eq 2 ]]'

setup_no_tag
# shellcheck disable=SC2034
run_deploy v2 && status=0 || status=$?
assert ".env에 API_TAG가 없으면 배포하지 않고 종료 코드 2로 끝난다" \
  '[[ $status -eq 2 ]] && grep -q "API_TAG" "$WORK/out.log" && [[ ! -f "$WORK/docker.log" ]]'

exit "$failures"
