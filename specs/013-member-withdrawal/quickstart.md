# Quickstart: 회원 탈퇴 검증 (013-member-withdrawal)

## 자동 검증

```bash
cd apps/api && ./gradlew test --tests "*Withdrawal*" --tests "*ModularityTests*" --tests "*ContractComparisonTest*" --tests "*FlywayMigrationTests*"
cd apps/api && ./gradlew clean build
pnpm --filter web test
pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web typecheck
pnpm --filter web test:e2e:full          # withdrawal.spec.ts
```

## 수동 검증 (로컬, e2e 프로필)

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | 회원 A로 로그인해 마이페이지의 프로필 수정으로 간다 | 맨 아래에 탈퇴로 가는 길이 있다 | US1-AC1 |
| 2 | 탈퇴 화면을 연다 | 사라지는 것, 남는 것, 되돌릴 수 없다는 것이 적혀 있다. 확인 칸을 채우기 전에는 탈퇴 버튼이 눌리지 않는다 | US1-AC2, US1-AC3 |
| 3 | 취소를 누른다 | 프로필 수정으로 돌아가고 아무것도 바뀌지 않는다 | US1-AC7 |
| 4 | A가 글 둘을 쓰고, 회원 B의 글에 댓글을 달고 공감한다. B가 A의 글에 댓글을 단다. 다른 창에도 A로 로그인해 둔다 | (준비) | |
| 5 | A가 확인 칸을 채우고 탈퇴한다 | 탈퇴했다는 안내와 함께 로그인 화면으로 간다 | US1-AC4 |
| 6 | 다른 창에서 아무 메뉴나 누른다 | 로그인 화면으로 간다 | US1-AC6 |
| 7 | A의 이메일과 비밀번호로 로그인한다 | 없는 계정과 같은 안내다 | US1-AC5 |
| 8 | B가 피드, A의 글 주소, 자기 글의 댓글 목록, "내 댓글", "공감한 글"을 본다. 로그아웃한 창에서 둘러보기를 본다 | A의 글과 댓글이 어디에도 없다. A의 글 주소는 지운 글 안내다. B의 글의 댓글 수가 하나 줄었다 | US2-AC1~AC6 |
| 9 | B의 글의 공감 수와 몬스터 HP를 탈퇴 전과 견준다 | 같다 | US3-AC1, US3-AC2 |
| 10 | B의 알림 목록을 본다 | A가 남긴 댓글과 공감의 알림이 남아 있고 닉네임 자리가 "누군가"다 | US3-AC4 |
| 11 | DB에서 A의 행을 본다(아래 SQL) | 개인 정보 열이 모두 NULL이고 본문이 비어 있다 | US2-AC8 |
| 12 | A의 이메일로 다시 가입해 예전 닉네임으로 온보딩한다 | 된다. 마이페이지, 알림, 도감이 비어 있다 | US4-AC1, AC3, AC4 |
| 13 | 가입만 하고 온보딩 화면에서 "가입 그만두기"로 탈퇴한다 | 같은 확인을 거쳐 탈퇴한다 | US1-AC9 |
| 14 | `[위기]` 글을 쓴 회원으로 탈퇴 화면을 연다 | 도움받을 곳이 함께 보인다. 탈퇴는 된다 | US1-AC10 |
| 15 | 운영자로 지정한 회원으로 탈퇴한다 | 운영자 지정을 먼저 풀라는 안내가 보이고 탈퇴되지 않는다 | US1-AC11 |
| 16 | 글이 수백 개인 회원이 탈퇴한 직후 API를 내렸다가 다시 올린다 | 올라온 뒤 그 회원의 본문이 모두 비워진다(11번의 SQL) | US5-AC3 |

```sql
-- 11번. :id는 A의 회원 번호
select id, auth_method, email, nickname, nickname_key, job_role, career_year, onboarded_at, withdrawn_at from member where id = :id;
select count(*) from posts where author_id = :id and content <> '';
select count(*) from comments where author_id = :id and content <> '';
select count(*) from oauth_identity where member_id = :id;
select count(*) from auth_session where member_id = :id;
select count(*) from notification where receiver_id = :id;
select count(*) from weekly_report where member_id = :id;
```

모두 0이거나 NULL이어야 한다(`id`, `auth_method`, `withdrawn_at`만 값이 있다).

## 운영

정리가 끝나지 않은 탈퇴는 운영자 토큰으로 본다.

```bash
curl -s -H "Authorization: Bearer $OPERATOR_TOKEN" https://<api>/api/v1/operator/withdrawals/cleanup
# {"success":true,"data":{"pending":0,"oldestWithdrawnAt":null},"error":null}
```

`pending`이 한참 0이 아니면 로그에서 `이벤트 발행이 10번 실패해`를 찾는다. 원인을 고친 뒤 API를 다시 시작하면 끝나지 않은 발행이 다시 나간다(`apps/api/AGENTS.md`의 "이벤트 재전송").

## 배포

병합하면 웹은 바로 배포된다. **API는 따로 올려야 한다**(`infra/gcp/README.md` 4절). 마이그레이션 `V13`이 `member`에 열 하나와 제약을, `comments`에 색인 하나를 더한다. API가 올라가기 전에는 탈퇴 버튼을 누르면 "탈퇴하지 못했어요"가 보이고 계정은 그대로다.

백업을 되살릴 때의 주의는 `infra/RESTORE.md`에 있다.
