# 운영 DB 백업과 복구

## 백업 설정 (한 번)

1. Cloudflare R2에 버킷 `ogu-backup`을 만든다.
2. 버킷 Settings > Object lifecycle rules에서 `postgres/` 접두사 객체를 14일 뒤 삭제하는 규칙을 추가한다.
3. R2 API 토큰(해당 버킷 Object Read & Write)을 만들고 `/opt/ogu/.env`의 `RCLONE_CONFIG_R2_*`를 채운다. `RCLONE_CONFIG_R2_ENDPOINT`는 `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`이다. 버킷 범위 토큰은 버킷 존재 확인/생성 권한이 없으므로 `RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true`가 `.env.example`에 이미 들어있는지 확인한다.
4. 수동으로 한 번 실행해 본다.

```bash
/opt/ogu/repo/infra/scripts/backup.sh
```

5. 크론에 등록한다. UTC 18:30은 한국 시간 03:30이다.

```bash
(crontab -l 2>/dev/null; echo '30 18 * * * /opt/ogu/repo/infra/scripts/backup.sh >> /opt/ogu/backup.log 2>&1') | crontab -
```

**US5-AC1 검증**: 다음 날 아래 명령에 그날 날짜의 덤프가 보이면 통과다.

```bash
set -a; source /opt/ogu/.env; set +a
rclone lsl "r2:$R2_BUCKET/postgres/" | tail -3
```

## 복구 연습 (분기마다, US5-AC2)

운영 DB를 건드리지 않고 임시 컨테이너에 복구해 본다.

```bash
set -a; source /opt/ogu/.env; set +a
LATEST="$(rclone lsf "r2:$R2_BUCKET/postgres/" | sort | tail -1)"
rclone copyto "r2:$R2_BUCKET/postgres/$LATEST" /tmp/restore.dump

docker run -d --rm --name ogu-restore -e POSTGRES_PASSWORD=restore pgvector/pgvector:pg17
until docker exec ogu-restore pg_isready -U postgres > /dev/null 2>&1; do sleep 1; done
docker exec -i ogu-restore pg_restore -U postgres -d postgres --no-owner < /tmp/restore.dump
docker exec ogu-restore psql -U postgres -tAc "select count(*) from flyway_schema_history"
docker compose -f /opt/ogu/repo/infra/compose.prod.yaml --env-file /opt/ogu/.env exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "select count(*) from flyway_schema_history"

docker rm -f ogu-restore && rm /tmp/restore.dump
```

두 숫자가 같으면 통과다. 날짜와 결과를 이 문서 맨 아래 기록 표에 남긴다.

## 실제 복구 (장애 시)

1. API를 멈춘다: `docker compose -f /opt/ogu/repo/infra/compose.prod.yaml --env-file /opt/ogu/.env stop api`
2. 위 연습 절차로 덤프를 받는다.
3. 운영 DB에 덮어쓴다.

```bash
docker compose -f /opt/ogu/repo/infra/compose.prod.yaml --env-file /opt/ogu/.env exec -T postgres \
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner < /tmp/restore.dump
```

4. API를 띄우고 헬스를 확인한다: `... start api && curl -s http://127.0.0.1:8080/actuator/health`

## 복구 연습 기록

| 날짜 | 덤프 | flyway_schema_history (복구/운영) | 결과 |
|---|---|---|---|
