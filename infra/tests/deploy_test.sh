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
  printf 'API_TAG=v1\nAPI_DOMAIN=api.example.com\nPOSTGRES_DB=ogu\n' > "$WORK/.env"
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

# 새 태그의 이미지를 받지 못하는 상황(인증 실패, 존재하지 않는 태그 등):
# docker 스텁이 API_TAG=missing일 때만 실패한다
setup_pull_fail() {
  WORK="$(mktemp -d)"
  mkdir -p "$WORK/bin"
  printf 'API_TAG=v1\nAPI_DOMAIN=api.example.com\nPOSTGRES_DB=ogu\n' > "$WORK/.env"
  touch "$WORK/compose.prod.yaml"

  cat > "$WORK/bin/docker" <<EOF
#!/usr/bin/env bash
if grep -q '^API_TAG=missing' "$WORK/.env"; then exit 1; fi
echo "\$*" >> "$WORK/docker.log"
EOF
  cat > "$WORK/bin/curl" <<EOF
#!/usr/bin/env bash
echo '{"status":"UP"}'
EOF
  chmod +x "$WORK/bin/docker" "$WORK/bin/curl"
}

# API_TAG가 없는 .env: current_tag()가 조기에 실패해야 한다(docker는 절대 호출되지 않아야 한다)
setup_no_tag() {
  WORK="$(mktemp -d)"
  mkdir -p "$WORK/bin"
  printf 'API_DOMAIN=api.example.com\nPOSTGRES_DB=ogu\n' > "$WORK/.env"
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

# API_DOMAIN이 없는 .env: check_api_domain()이 조기에 실패해야 한다(docker는 절대 호출되지 않아야 한다)
setup_no_domain() {
  WORK="$(mktemp -d)"
  mkdir -p "$WORK/bin"
  printf 'API_TAG=v1\nPOSTGRES_DB=ogu\n' > "$WORK/.env"
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

setup_pull_fail
# shellcheck disable=SC2034
run_deploy missing && status=0 || status=$?
assert "새 이미지를 받지 못하면 이전 태그로 롤백하고 실패한다" \
  '[[ $status -eq 1 ]] && grep -q "^API_TAG=v1$" "$WORK/.env"'

setup_no_domain
# shellcheck disable=SC2034
run_deploy v2 && status=0 || status=$?
assert ".env에 API_DOMAIN이 없으면 배포하지 않고 종료 코드 2로 끝난다" \
  '[[ $status -eq 2 ]] && grep -q "API_DOMAIN" "$WORK/out.log" && [[ ! -f "$WORK/docker.log" ]]'

# 004 운영 준비(quickstart 1, 4): compose와 Caddyfile을 정적으로 검사한다.
# compose 파일에서 최상위 서비스 하나의 블록(다음 서비스나 최상위 키 전까지)을 뽑는다.
service_block() {
  awk -v name="$1" '
    $0 ~ "^  " name ":$" { inside = 1; next }
    inside && ($0 ~ /^  [^ #]/ || $0 ~ /^[^ #]/) { inside = 0 }
    inside { print }
  ' "$2"
}

# 서비스 블록의 depends_on에서 대상 서비스의 condition 값을 뽑는다.
# 아래 assert의 eval 문자열 안에서만 부르므로 shellcheck는 사용처를 보지 못한다
# shellcheck disable=SC2329
depends_condition() {
  awk -v target="$2" '
    /^    depends_on:/ { deps = 1; next }
    deps && /^    [^ ]/ { deps = 0 }
    deps && $0 ~ "^      " target ":$" { want = 1; next }
    want && /condition:/ { print $2; exit }
  ' <<< "$1"
}

# 아래 변수는 assert의 eval 문자열 안에서만 쓴다
PROD_COMPOSE="$ROOT/compose.prod.yaml"
# shellcheck disable=SC2034
CADDYFILE="$ROOT/Caddyfile"
WORK="$(mktemp -d)" # assert가 실패하면 출력하는 로그 자리
: > "$WORK/out.log"
# shellcheck disable=SC2034
redis_block="$(service_block redis "$PROD_COMPOSE")"
# shellcheck disable=SC2034
api_block="$(service_block api "$PROD_COMPOSE")"

assert "운영 compose에 redis 서비스가 있고 포트를 공개하지 않는다" \
  '[[ -n "$redis_block" ]] && ! grep -q "ports:" <<< "$redis_block"'

assert "운영 redis는 디스크에 저장하지 않는다(--save \"\", --appendonly no)" \
  'grep -qF -- "--save \"\"" <<< "$redis_block" && grep -qF -- "--appendonly no" <<< "$redis_block"'

assert "api는 redis에 service_started로만 의존한다(Redis 장애가 기동을 막지 않는다)" \
  '[[ "$(depends_condition "$api_block" redis)" == "service_started" ]]'

assert "Caddyfile은 알림 스트림 경로를 압축에서 뺀다" \
  'grep -qE "^[[:space:]]*@compressible not path /api/v1/notifications/stream$" "$CADDYFILE" \
    && grep -qE "^[[:space:]]*encode @compressible " "$CADDYFILE" \
    && ! grep -qE "^[[:space:]]*encode zstd" "$CADDYFILE"'

exit "$failures"
