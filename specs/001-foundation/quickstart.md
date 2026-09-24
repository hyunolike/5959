# M0 운영 환경 준비

코드로 할 수 없는 계정 설정과 수동 검증 절차다. 위에서부터 순서대로 한다.

## 1. main 브랜치

```bash
git switch develop && git pull && git switch -c main && git push -u origin main
```

`main`은 `develop`에서 PR로만 갱신한다. release-please가 `main`에 릴리즈 PR을 만든다.

## 2. Oracle Cloud VM

1. Oracle Cloud 무료 계정을 만들고 홈 리전을 서울(ap-seoul-1) 또는 춘천(ap-chuncheon-1)으로 고른다. 홈 리전은 나중에 바꿀 수 없다.
2. Compute > Instances에서 `VM.Standard.A1.Flex`(4 OCPU, 24GB), Ubuntu 24.04 이미지로 인스턴스를 만든다. SSH 공개키를 등록한다.
3. VCN 보안 목록에 TCP 80, 443 인바운드를 추가한다.
4. VM에 접속해서 초기 설정을 한다.

```bash
ssh ubuntu@<VM_IP>
curl -fsSL https://raw.githubusercontent.com/hyunolike/5959/develop/infra/scripts/bootstrap-vm.sh | bash
```

5. `/opt/ogu/.env`를 채운다. `API_DOMAIN`은 `api.<VM_IP의 점을 대시로>.sslip.io` 형식으로 쓴다(예: `api.203-0-113-10.sslip.io`). `POSTGRES_PASSWORD`는 `openssl rand -base64 24`로 만든다.

## 3. GitHub 설정

1. Settings > Environments에서 `production` 환경을 만든다.
2. `production` 환경 시크릿을 등록한다.

```bash
gh secret set VM_HOST --env production --body "<VM_IP>"
gh secret set VM_USER --env production --body "ubuntu"
gh secret set VM_SSH_KEY --env production < ~/.ssh/<배포용_개인키>
```

3. `deploy` job이 실행될 때마다 워크플로 토큰으로 VM을 ghcr.io에 로그인시키고 배포가 끝나면 로그아웃한다. GHCR 패키지 `5959-api`는 비공개로 둬도 된다.

## 4. Grafana Cloud (US4-AC1)

1. Grafana Cloud 무료 스택을 만든다.
2. Connections > Add new connection > OpenTelemetry (OTLP)에서 토큰을 만들고, 화면에 나온 `OTEL_EXPORTER_OTLP_ENDPOINT`와 `OTEL_EXPORTER_OTLP_HEADERS` 값을 `/opt/ogu/.env`에 넣는다.
3. `docker compose -f /opt/ogu/repo/infra/compose.prod.yaml --env-file /opt/ogu/.env up -d api`로 다시 띄운다.
4. **수동 검증**: `curl https://<API_DOMAIN>/actuator/health`를 몇 번 호출한 뒤 Grafana의 Explore > Tempo에서 `service.name = ogu-api`로 트레이스를 찾는다. 같은 trace ID로 Loki에서 로그가 나오면 US4-AC1 통과다.

## 5. Vercel

1. Vercel에서 `hyunolike/5959`를 Import하고 Root Directory를 `apps/web`으로 지정한다.
2. Production Branch를 `main`으로 바꾼다.
3. 환경변수 `API_ORIGIN=https://<API_DOMAIN>`을 Production과 Preview에 넣는다.

## 6. 첫 배포 (US3-AC1)

1. `develop` → `main` PR을 머지한다. release-please가 `chore(main): release api 0.1.0` PR을 만든다.
2. 그 PR을 머지하면 `Release` 워크플로가 이미지를 올리고 VM에 배포한다.
3. **검증**: `curl https://<API_DOMAIN>/actuator/health`가 `UP`이고, Vercel 운영 URL 첫 화면에 "서버 정상"이 보이면 US3-AC1과 SC-001 통과다.

## 7. 백업 (US5-AC1, US5-AC2)

`infra/RESTORE.md`를 따른다.
