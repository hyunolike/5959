#!/usr/bin/env bash
# 기동 캐시(AOT)를 담은 API 이미지를 만든다(018 research R1).
#
# 캐시는 학습 실행(컨텍스트를 끝까지 올린 뒤 바로 끝남)으로 만든다. 학습에는 DB가 있어야 해서
# 빈 Postgres를 잠깐 띄우고 마이그레이션까지 돌린다. 학습에 쓰는 비밀값은 모두 가짜다.
#
# 쓰는 법: infra/render/scripts/build-image.sh <이미지 태그> [플랫폼]
#   먼저 apps/api에서 ./gradlew bootJar로 jar를 만든다. 플랫폼 기본값은 linux/amd64(Render).
#   캐시는 같은 JDK, 같은 CPU 종류에서만 쓸 수 있다. 다른 플랫폼에서 돌리면 에뮬레이션이라 느리다.
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "쓰는 법: $0 <이미지 태그> [플랫폼]" >&2
  exit 2
fi

TAG="$1"
PLATFORM="${2:-linux/amd64}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
API_DIR="$ROOT/apps/api"
RUN_ID="ogu-aot-$$"
NETWORK="$RUN_ID"
DB="$RUN_ID-db"
TRAIN="$RUN_ID-train"
# Dockerfile의 ENTRYPOINT와 같아야 한다. 초기 힙이 다르면 캐시를 쓰지 못한다.
# 메타스페이스와 코드 캐시 상한은 학습에서 빼 둔다. 넣으면 캐시를 조립하는 단계가 조용히 실패한다.
HEAP_FLAGS=(-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xms64m -Xmx192m)

# 학습과 확인에 쓰는 운영 프로필 설정. 값은 모두 가짜다.
APP_ENV=(
  -e SPRING_PROFILES_ACTIVE=prod
  -e "DB_URL=jdbc:postgresql://$DB:5432/ogu" -e DB_USERNAME=ogu -e DB_PASSWORD=ogu
  -e JWT_SECRET=aot-training-only-aot-training-only-0123456789
  -e OGU_BFF_KEY=aot-training-only
  -e "OAUTH_ALLOWED_REDIRECT_URIS=https://aot.invalid/api/auth/oauth/kakao/callback,https://aot.invalid/api/auth/oauth/google/callback"
  -e KAKAO_CLIENT_ID=aot -e KAKAO_CLIENT_SECRET=aot -e GOOGLE_CLIENT_ID=aot -e GOOGLE_CLIENT_SECRET=aot
  -e AI_API_KEY=aot -e OGU_SSE_ALLOWED_ORIGINS=https://aot.invalid
  -e AI_COMPANION_ENABLED=false -e MAIL_ENABLED=false
)

cleanup() {
  docker rm -f "$TRAIN" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ ! -f "$API_DIR/build/libs/api.jar" ]]; then
  echo "apps/api/build/libs/api.jar가 없습니다. 먼저 ./gradlew bootJar를 돌리세요." >&2
  exit 1
fi

echo "1/4 캐시 없는 이미지를 빌드합니다 ($PLATFORM)"
docker build --platform "$PLATFORM" --target runtime -t "$TAG-runtime" "$API_DIR"

echo "2/4 학습용 Postgres를 띄웁니다"
docker network create "$NETWORK" >/dev/null
docker run -d --name "$DB" --network "$NETWORK" \
  -e POSTGRES_DB=ogu -e POSTGRES_USER=ogu -e POSTGRES_PASSWORD=ogu \
  pgvector/pgvector:pg17 >/dev/null
for _ in $(seq 1 30); do
  docker exec "$DB" pg_isready -U ogu -d ogu >/dev/null 2>&1 && break
  sleep 2
done

echo "3/4 학습 실행으로 캐시를 만듭니다"
# 학습에는 메모리 한도를 걸지 않는다. 512MB에서는 캐시를 쓰는 단계가 메모리 부족으로 죽는다.
# 캐시에 영향을 주는 초기 힙은 HEAP_FLAGS로 고정했고, 512MB에서 쓰이는지는 마지막 확인이 본다.
docker run --name "$TRAIN" --network "$NETWORK" --platform "$PLATFORM" \
  --entrypoint java \
  "${APP_ENV[@]}" \
  "$TAG-runtime" \
  "${HEAP_FLAGS[@]}" -XX:AOTCacheOutput=/tmp/app.aot -Dspring.context.exit=onRefresh -jar api.jar \
  >"$API_DIR/build/aot-training.log" 2>&1 || {
  echo "학습 실행이 실패했습니다. apps/api/build/aot-training.log를 보세요." >&2
  exit 1
}
mkdir -p "$API_DIR/build/aot"
docker cp "$TRAIN:/tmp/app.aot" "$API_DIR/build/aot/app.aot"

echo "4/4 캐시를 담은 이미지를 빌드합니다"
docker build --platform "$PLATFORM" --target with-aot -t "$TAG" "$API_DIR"

# 캐시를 실제로 쓰는지 본다. AOTMode=on이면 캐시를 못 쓸 때 JVM이 뜨지 않는다.
# 이미지의 ENTRYPOINT 그대로(JDK_JAVA_OPTIONS의 캐시 포함) 컨텍스트를 올렸다가 끝낸다.
echo "확인: 캐시를 쓰고 기동하는지 봅니다"
if ! docker run --rm --network "$NETWORK" --platform "$PLATFORM" --memory=512m \
  "${APP_ENV[@]}" -e "JAVA_TOOL_OPTIONS=-XX:AOTMode=on -Dspring.context.exit=onRefresh" \
  "$TAG" >"$API_DIR/build/aot-check.log" 2>&1; then
  echo "캐시를 쓰고 뜨지 못했습니다. apps/api/build/aot-check.log를 보세요." >&2
  exit 1
fi
echo "완료: $TAG ($(du -h "$API_DIR/build/aot/app.aot" | cut -f1) 캐시)"
