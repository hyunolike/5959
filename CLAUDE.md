# 오구오구 (5959)

모노레포다. 작업 전에 해당 앱의 AGENTS.md를 읽는다.

- `apps/api`: Kotlin, Spring Boot 4, Spring Modulith. 지침은 `apps/api/AGENTS.md`
- `apps/web`: Next.js 16, FSD. 지침은 `apps/web/AGENTS.md`
- `infra`: 운영 compose, 배포와 백업 스크립트. 테스트는 `bash infra/tests/deploy_test.sh`
- `webbb-be`, `webbb-fe`: 원본 참고용 subtree. **수정하지 않는다.**

## 설계와 스펙

- 전체 설계: `docs/architecture/overview.md`, 결정 기록: `docs/adr/`
- 원칙: `.specify/memory/constitution.md`
- 기능 스펙: `specs/NNN-이름/`. 새 기능은 `speckit-specify` 스킬부터 시작한다.

## 규칙

- 커밋 메시지와 PR 제목은 Conventional Commits.
- 테스트 이름에 스펙 인수 조건 ID(`US1-AC2`)를 넣는다.
- PR은 `develop`으로 보낸다. 필수 체크는 `ci-ok`.
