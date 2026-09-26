# 오구오구 Constitution

## Core Principles

### I. 경계는 테스트로 강제한다
백엔드 모듈 경계는 `ApplicationModules.verify()`가, 프론트엔드 레이어 규칙은 steiger가 CI에서 검사한다.
검사를 느슨하게 만들어 통과시키지 않는다. 코드를 고친다.

### II. 계약이 코드보다 먼저다
API를 추가하거나 바꾸는 스펙은 plan 단계에서 `contracts/openapi.yaml`을 먼저 작성한다.
백엔드 구현과 프론트엔드 생성 타입은 계약과 일치해야 한다.

### III. 인수 조건은 곧 테스트다
스펙의 인수 조건에는 `US{n}-AC{m}` ID를 붙이고, 그 조건을 검증하는 테스트 이름에 같은 ID를 넣는다.
자동화할 수 없는 조건은 `quickstart.md`에 수동 검증 절차로 적는다.

### IV. 사용자 안전이 기능보다 먼저다
위기 신호 감지와 대응은 사용자 참여를 늘리는 기능보다 먼저 출시한다.
안전 판단은 AI 장애 때문에 빠지지 않도록 규칙 기반 대체 경로를 둔다.

### V. AI 장애가 핵심 흐름을 막지 않는다
AI 호출은 비동기 이벤트 뒤에 둔다. AI가 실패해도 사용자의 쓰기 요청은 성공하고, 이벤트는 재처리된다.

### VI. 무료 인프라 안에서 운영한다
월 인프라 비용은 AI 사용료를 빼고 0원이다. 비용이 드는 선택은 ADR(`docs/adr/`)로 근거를 남긴다.

## 개발 흐름

기능마다 `speckit-specify` → `speckit-clarify` → `speckit-plan` → `speckit-tasks` → `speckit-analyze` → `speckit-implement` 순서로 진행한다.
PR 본문에는 스펙 링크와 인수 조건 체크리스트를 넣는다. 커밋 메시지와 PR 제목은 Conventional Commits를 따른다.

## Governance

이 문서는 다른 모든 관행보다 우선한다. 원칙을 바꾸려면 PR에서 이유를 적고 버전을 올린다.
plan 단계의 Constitution Check는 여기 적힌 원칙을 기준으로 한다.

**Version**: 1.0.0 | **Ratified**: 2026-09-24 | **Last Amended**: 2026-09-24
