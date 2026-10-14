# Quickstart: 닉네임 변경 제한 검증 (014-nickname-change-limit)

## 자동 검증

```bash
cd apps/api && ./gradlew test --tests "*ProfileUpdateApiTests*" --tests "*Withdrawal*" --tests "*ContractTests*" --tests "*FlywayMigrationTests*"
cd apps/api && ./gradlew ktlintCheck detekt
pnpm --filter web test
pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web typecheck
pnpm --filter web test:e2e:full          # mypage.spec.ts
```

## 수동 검증 (로컬, e2e 프로필)

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | 새 회원으로 프로필 수정을 연다 | 닉네임 칸 아래에 "닉네임을 바꾸면 30일 동안 다시 바꿀 수 없어요." | US2-AC1 |
| 2 | 닉네임을 바꿔 저장하고 프로필 수정을 다시 연다 | 저장된다. 닉네임 칸이 잠겨 있고 언제부터 바꿀 수 있는지 보인다 | US1-AC1, US2-AC2, US2-AC6 |
| 3 | 직군을 바꿔 저장한다 | 저장되고 마이페이지로 돌아간다 | US1-AC3, US2-AC3 |
| 4 | 개발자 도구에서 `PATCH /api/members/me`에 다른 닉네임을 보낸다 | 409 `NICKNAME_CHANGE_LIMITED`, 닉네임은 그대로 | US1-AC2 |
| 5 | DB에서 `update member set nickname_locked_until = now() - interval '1 minute' where id = :id` 한 뒤 닉네임을 바꾼다 | 저장되고 다시 잠긴다 | US1-AC5 |

## 배포

병합하면 웹은 바로 배포된다. **API는 따로 올려야 한다.** 올리면 `V14`가 `member`에 열 하나를 더한다. API가 올라가기 전에는 프로필 응답에 새 값이 없어 화면은 "바꿀 수 있다"로 보이고, 서버도 아직 제한하지 않는다. 어긋나는 것은 없다.
