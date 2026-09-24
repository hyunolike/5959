# Feature Specification: 서비스 기반

**Feature Branch**: `001-foundation`
**Created**: 2026-09-24
**Status**: Draft
**Input**: 두 템플릿을 모노레포로 이식하고, CI, 배포, 백업, 관측성을 갖춘 빈 서비스를 운영 URL에 띄운다.

## User Scenarios & Testing

### User Story 1 - 방문자가 서비스와 서버 상태를 확인한다 (Priority: P1)

채용 담당자가 운영 URL에 접속하면 오구오구 첫 화면과 서버 상태를 본다.

**Why this priority**: 이후 모든 기능이 올라갈 배포 경로(브라우저 → BFF → API)가 실제로 연결됐다는 증거다.

**Independent Test**: 운영 URL을 열어 첫 화면과 "서버 정상" 표시를 확인한다.

**Acceptance Scenarios**:

1. **US1-AC1** **Given** API가 정상일 때, **When** 첫 화면을 열면, **Then** "서버 정상"이 표시된다.
2. **US1-AC2** **Given** API가 응답하지 않을 때, **When** 첫 화면을 열면, **Then** 페이지는 정상적으로 뜨고 "서버 점검 중"이 표시된다.

### User Story 2 - 개발자의 PR이 자동으로 검사된다 (Priority: P1)

**Why this priority**: 아키텍처 규칙을 사람이 아니라 CI가 지키게 하는 것이 이 프로젝트의 핵심 주장이다.

**Independent Test**: 규칙을 어기는 커밋을 올린 PR에서 `ci-ok`가 실패하는지 본다.

**Acceptance Scenarios**:

1. **US2-AC1** **Given** `apps/api`만 바뀐 PR일 때, **When** CI가 돌면, **Then** API 검사만 실행되고 웹 검사는 건너뛰며 `ci-ok`는 성공한다.
2. **US2-AC2** **Given** 한 모듈이 다른 모듈의 내부 패키지를 참조할 때, **When** CI가 돌면, **Then** `ModularityTests`가 실패하고 `ci-ok`도 실패한다.
3. **US2-AC3** **Given** PR 제목이 Conventional Commits 형식이 아닐 때, **When** CI가 돌면, **Then** `ci-ok`가 실패한다.

### User Story 3 - 릴리즈가 자동으로 배포되고, 실패하면 롤백된다 (Priority: P2)

**Independent Test**: release-please 릴리즈 PR을 머지하고 운영 헬스 체크를 확인한다.

**Acceptance Scenarios**:

1. **US3-AC1** **Given** release-please가 API 릴리즈를 만들었을 때, **When** 워크플로가 끝나면, **Then** 새 태그 이미지가 GHCR에 올라가고 운영 `/actuator/health`가 `UP`이다.
2. **US3-AC2** **Given** 새 이미지가 헬스 체크를 통과하지 못할 때, **When** 배포 스크립트가 돌면, **Then** 이전 태그로 되돌리고 실패로 끝난다.

### User Story 4 - 운영자가 요청을 추적한다 (Priority: P3)

**Acceptance Scenarios**:

1. **US4-AC1** **Given** 운영 API에 요청을 보냈을 때, **When** Grafana Cloud에서 서비스 `ogu-api`를 조회하면, **Then** 해당 요청의 트레이스와 trace ID가 붙은 로그가 보인다. (수동 검증: `quickstart.md`)

### User Story 5 - 운영 DB를 백업하고 복구할 수 있다 (Priority: P3)

**Acceptance Scenarios**:

1. **US5-AC1** **Given** 백업 크론이 설정됐을 때, **When** 하루가 지나면, **Then** R2의 `postgres/` 아래에 그날의 덤프가 있다. (수동 검증)
2. **US5-AC2** **Given** 최신 덤프가 있을 때, **When** `infra/RESTORE.md`의 연습 절차를 따르면, **Then** 빈 컨테이너에 복구되고 `flyway_schema_history` 행 수가 운영과 같다. (수동 검증)

### Edge Cases

- API가 느리게 응답하면 BFF는 3초 뒤 포기하고 `DOWN`으로 응답한다.
- 롤백한 이전 태그도 헬스 체크에 실패하면 스크립트는 실패로 끝나고 로그에 두 태그를 모두 남긴다.

## Requirements

### Functional Requirements

- **FR-001**: 저장소는 `apps/api`, `apps/web`을 가진 모노레포여야 한다.
- **FR-002**: API는 `com.ogu` 패키지에서 Spring Modulith 모듈 검증을 테스트와 기동 시점 모두에서 수행해야 한다.
- **FR-003**: API 스키마는 Flyway로만 관리하며, V1에서 `vector` 확장과 `event_publication` 테이블을 만든다.
- **FR-004**: 웹은 `/api/health` BFF 라우트로만 API 상태를 조회한다. 브라우저가 API 도메인을 직접 호출하지 않는다.
- **FR-005**: CI는 바뀐 경로의 검사만 실행하고, 모든 결과를 `ci-ok` 한 개의 체크로 모은다.
- **FR-006**: `main`에 API 릴리즈가 생기면 멀티 아키텍처 이미지를 GHCR에 올리고 VM에 배포한다.
- **FR-007**: 배포 스크립트는 헬스 체크 실패 시 이전 태그로 롤백한다.
- **FR-008**: 운영 API는 OpenTelemetry로 트레이스, 메트릭, 로그를 Grafana Cloud로 보낸다.
- **FR-009**: 운영 DB는 매일 R2로 백업하고, 복구 절차가 문서로 있어야 한다.

## Success Criteria

- **SC-001**: 운영 URL 첫 화면이 "서버 정상"을 표시한다.
- **SC-002**: `develop` 브랜치 보호 규칙의 필수 체크가 `ci-ok` 하나다.
- **SC-003**: 롤백 시나리오 테스트(`infra/tests/deploy_test.sh`)가 CI에서 통과한다.
- **SC-004**: 월 인프라 청구액 0원.
