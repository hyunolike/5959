# GCP Terraform Deployment Implementation Plan

> **For agentic workers:** Execute inline with superpowers:executing-plans. Track the checklist below; user authorization is to prepare and deploy this configuration after showing its architecture.

**Goal:** Terraform으로 작은 GCP VM과 Cloud Run 웹을 배포하고 핵심 흐름을 검증한다.
**Architecture:** 기존 Compose를 VM으로 옮기고 Next.js 웹은 Cloud Run에서 요청 기반으로 실행한다. Terraform은 자원과 IAM을 관리하며 운영 시크릿 값은 별도 업로드한다.
**Tech Stack:** Terraform >=1.7, hashicorp/google 7.x, gcloud, Docker Compose, Python 3, Cloud Build.
**Spec:** `docs/superpowers/specs/2026-10-11-gcp-terraform-design.md`

## Global Constraints

- 기본 프로젝트를 묵시적으로 사용하지 않는다. 사용자가 대상 project_id와 리전을 정해야 실제 배포한다.
- 시크릿 값은 state, metadata, 빌드 이미지에 넣지 않는다.
- 데이터 디스크는 자동 삭제하지 않고 prevent_destroy로 보호한다.
- 기존 원본 subtree는 수정하지 않는다.
- 실제 apply 전에 시스템 구조와 saved plan의 변경 목록을 보여 준다.

## Review Focus

- 재부팅과 bootstrap 재실행이 기존 DB를 포맷하거나 초기화하지 않는지 확인한다.
- 이미지 pull/health 실패가 직전 정상 이미지와 기존 설정을 보존하는지 검증한다.
- Cloud Run 설정 누락이 localhost와 개발 BFF 키로 조용히 넘어가지 않게 검증한다.
- 비밀 값의 특수 문자가 env 파일을 깨거나 배포 로그에 드러나지 않게 검증한다.
- 비용을 만드는 apply가 프로젝트 미확정 상태에서 실행되지 않게 검증한다.

## Task 1: Terraform 기반 자원

**Files:** `infra/gcp/terraform/*.tf`, `infra/gcp/terraform/tests/*.tftest.hcl`
**Interfaces:** Outputs project_id, region, zone, api_ip, api_origin, web_origin, registry, vm_name, secret_ids.

- [x] Terraform mock-provider plan tests로 공개 포트, VM 사양, 데이터 보호, 시크릿 값 미관리와 웹 최소 0을 검증한다.
- [x] `terraform init -backend=false`, `terraform fmt -check`, `terraform validate`, `terraform test`를 실행한다.
- [ ] test 통과를 확인한 뒤 실제 target project의 saved plan을 확인한다.

## Task 2: VM 실행과 백업

**Files:** `infra/gcp/vm/{bootstrap.sh,runtime.py,backup.py,compose.yaml}`, `infra/gcp/tests/test_runtime.py`
**Interfaces:** VM metadata의 공개 deployment-config를 읽고 Secret Manager의 세 비밀 값으로 `.env.json`을 만든다.

- [x] `python3 -m unittest discover -s infra/gcp/tests -v`에서 특수문자, 누락 키, pull 실패와 롤백 테스트가 실패함을 확인한다.
- [x] 기존 디스크를 포맷하지 않는 초기 설정, 원자적 env 교체, 실패 시 복구, 14일 백업 보관을 구현한다.
- [x] Python 테스트와 `shellcheck infra/gcp/vm/*.sh`를 통과시킨다.

## Task 3: 웹 이미지와 운영 설정

**Files:** `apps/web/Dockerfile`, `apps/web/next.config.ts`, `apps/web/src/shared/config/env{,.test}.ts`, `.dockerignore`, `infra/gcp/cloudbuild.yaml`
**Interfaces:** Build context는 repo root, Next.js standalone 결과와 public/static을 복사한다. 운영 키는 실행 때만 주입한다.

- [x] APP_ENV=production에서도 API_ORIGIN/BFF_API_KEY/APP_ORIGIN 필수 조건을 테스트에 먼저 추가한다.
- [x] standalone 이미지와 Cloud Build 구성을 만들고 생성한 이미지 태그를 Terraform 변수로 넘긴다.
- [x] 타입 검사, lint, 환경변수 테스트, production build를 통과시킨다.

## Task 4: CLI 배포 절차와 실배포

**Files:** `infra/gcp/{README.md,terraform/terraform.tfvars.example}`, `infra/gcp/scripts/*.sh`, `.gitignore`, `.github/workflows/ci.yml`
**Interfaces:** Terraform 기반 리소스 apply → 실제 키 업로드 → 이미지 build → 이미지 plan/apply → IAP API deploy → 웹/API 검증.

- [x] gcloud 명령은 모두 명시적인 project를 사용하고 시크릿 파일은 build context에서 제외한다.
- [x] 문서에 구조도, 비용, 단계별 명령, 백업 복구와 회수 절차를 기록한다.
- [x] 기존 infra 테스트와 새 검사를 CI에 연결한다.
- [ ] 대상과 필수 값이 확정되면 actual plan을 확인하고 배포, 아니면 미완료 이유를 명시한다.

## 검증 결과와 남은 작업

- Terraform validate 및 mock tests 2개 통과. 실제 클라우드 리소스는 생성하지 않았다.
- 새 배포/Compose 테스트 14개와 기존 infra 테스트 9개 통과. shellcheck 통과.
- 웹 테스트 997개, typecheck, lint, FSD 검사 통과(lint의 기존 query-client 경고 1개).
- 로컬 production build는 webpack으로 통과했다. 기본 Turbopack 빌드는 실행 환경의 포트 권한 오류가 있어 대체했다.
- standalone production 서버를 실행해 `/api/health`가 지정한 API에 연결하고 HTTP 200/UP을 반환함을 확인했다.
- 독립 검토 후 데이터 디스크 UUID/Docker 시작 보호, JSON 비밀값 전달과 출력 차단, VM 크기 변경 지원을 보완했고 재검토를 통과했다.
- GCP project_id, 리전과 결제 연결, 실제 AI/OAuth 자격 증명이 미확정이다. 실제 saved plan/apply, Cloud Build 이미지 빌드, VM 기동/재부팅, 운영 기능과 백업 복구 검증은 남아 있다.
