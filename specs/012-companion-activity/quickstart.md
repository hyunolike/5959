# Quickstart: AI 친구의 활동 넓히기 검증 (012-companion-activity)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/companion-activity.openapi.yaml](contracts/companion-activity.openapi.yaml), 데이터는 [data-model.md](data-model.md), 결정 근거는 [research.md](research.md)를 본다. 009의 절차(`specs/009-ai-companions/quickstart.md`)가 먼저 통과해야 한다.

## 준비

```bash
# e2e 프로필: 가짜 댓글 쓰기, 기다리는 시간 5초, 뒤따르는 간격 2초에서 4초, 주기 2초
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e ./gradlew bootRun
pnpm --filter web dev
```

e2e 프로필에서 가짜 댓글 쓰기는 본문에 `[AI친구]`가 있는 글에만 답한다(009). e2e 프로필에서는 반응할 친구의 수도 무작위로 뽑지 않고 본문의 표지로 정한다(research R2). 이미 있는 e2e 시나리오의 댓글 수가 흔들리지 않게 한다.

| 본문의 표지 | 결과 |
|---|---|
| `[AI친구]`만 | 한 명이 반응한다 |
| `[AI친구:2]`, `[AI친구:3]` | 그 수만큼 반응한다(e2e 프로필에서만) |
| `[AI댓글되풀이]` | 뒤따르는 친구가 앞 댓글을 그대로 낸다. 검사에서 떨어진다 |

글쓰기는 e2e 프로필에서 꺼져 있다. 로컬에서 볼 때는 `AI_COMPANION_POSTING_ENABLED=true AI_COMPANION_POSTING_QUIET=1m`으로 띄운다. 가짜 글쓰기는 소재의 설명이 든 정해진 글을 낸다.

## 자동 검증

```bash
cd apps/api && ./gradlew test
pnpm --filter web test
pnpm --filter web test:e2e:full          # ai-companions.spec.ts
bash infra/tests/deploy_test.sh && python3 -m unittest discover infra/gcp/tests
# 실제 모델(키가 있을 때만). 사람이 읽는다
OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*CompanionCommentLiveTest*" -i | grep LIVE
OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*CompanionPostLiveTest*" -i | grep LIVE
```

## 수동 검증 시나리오: 여러 친구의 반응 (PR 1)

회원 A, B가 가입과 온보딩을 마친 상태에서 시작한다. "기다린다"는 e2e 프로필에서 20초쯤이다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | A가 `[AI친구:3] [불안:낮음]` 글을 쓰고 기다린다 | 서로 다른 AI 친구 셋의 댓글과 공감이 몇 초 간격으로 차례로 생긴다. HP가 12 줄었다. 알림이 친구마다 온다 | US1-AC4, AC5, AC8 |
| 2 | `select target_count, done_count, status, skip_reason from companion_reaction where post_id = ?` | 3, 3, `DONE`, null. `companion_reaction_entry`에 세 행 | US1-AC1, US7-AC4 |
| 3 | `./gradlew test --tests "*ReactionCountPickerTest*"` | 씨앗을 고정해 만 번 뽑은 비율이 60, 30, 10에서 2%p 안이다. 무게를 바꾸면 따라 바뀌고, 모두 0이면 1명이다 | US1-AC2, US7-AC2 |
| 4 | 서버를 다시 띄우고 3번의 행을 다시 본다 | `target_count`가 그대로다 | US1-AC3 |
| 5 | A가 `[AI친구:3]` 글을 쓰고, 첫 AI 댓글이 달리자마자 B가 댓글을 단다 | AI 친구의 댓글이 더 늘지 않는다. `done_count = 1`, `DONE`, `HUMAN_COMMENT` | US2-AC1 |
| 6 | A가 `[AI친구:3]` 글을 쓰고, 첫 AI 댓글 뒤에 글을 지운다. 다른 글은 운영자가 숨긴다 | 더 늘지 않는다. `GONE` | US2-AC2 |
| 7 | A가 `[AI친구:3]` 글을 쓰고, 첫 AI 댓글 뒤에 본문을 `[우려]`가 든 것으로 고친다 | 더 늘지 않는다. `RISK` | US2-AC3 |
| 8 | A가 `[AI친구:2] [AI댓글되풀이]` 글을 쓴다 | 댓글은 하나다. `done_count = 1`, `DONE`, `REJECTED` | US1-AC9, US2-AC7 |
| 9 | `AI_COMPANION_ENABLED=false`로 다시 띄우고 `[AI친구:3]` 글을 쓴다 | 반응이 없다 | US2-AC10 |
| 10 | e2e가 아닌 프로필에서 `AI_COMPANION_COUNT_WEIGHTS=0,0,100`으로 띄우고 글을 쓴다 | 그 글의 `target_count`가 3이다 | US7-AC2 |
| 11 | AI 친구 안내 화면과 글쓰기 화면의 안내를 읽는다 | 한 명 이상 답할 수 있다고 적혀 있다 | US4-AC6, AC7 |

## 수동 검증 시나리오: AI 친구의 글쓰기 (PR 2)

`AI_COMPANION_POSTING_ENABLED=true AI_COMPANION_POSTING_QUIET=1m`으로 띄운다. 한국 시간 8시부터 23시 사이에 한다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 20 | 1분 넘게 아무 글도 쓰지 않는다 | AI 친구의 글이 하나 올라온다. 감정과 몬스터가 붙는다 | US3-AC1, AC8 |
| 21 | 피드에서 그 글을 본다 | 직군과 경력 자리에 "AI 친구". 화면 낭독기도 그렇게 읽는다 | US4-AC1 |
| 22 | 글을 연다 | 닉네임 옆에 "AI 친구", 지어 쓴 이야기라는 안내, 안내 화면으로 가는 링크 | US4-AC2 |
| 23 | 로그아웃하고 홈의 둘러보기를 본다(위험 판정이 끝난 뒤) | 그 글이 있고 닉네임은 없다. "AI 친구" 표시가 있다 | US4-AC3 |
| 24 | 피드를 직군으로 거른다 | AI 친구의 글이 없다 | US4-AC5 |
| 25 | B가 그 글에 공감하고 댓글을 단다 | HP가 줄고, 처치되면 B의 도감에 든다. AI 회원의 알림 행은 0이다 | US3-AC9, US6-AC5 |
| 26 | 다른 AI 글이 올라온 뒤 10초를 기다린다(반응을 켠 채, 본문에 표지가 없으므로 테스트 컨텍스트나 실제 키로 본다) | 글쓴 친구가 아닌 AI 친구가 댓글을 단다. `target_count`는 1이나 2 | US6-AC1, AC2, AC3 |
| 27 | A가 글을 쓰고 1분을 기다린다 | AI 친구의 글이 올라오지 않는다(조용하지 않다) | US3-AC2 |
| 28 | `select companion_id, day, topic, status, give_up_reason, attempts from companion_post order by id` | 친구마다 하루 한 행. 소재가 겹치지 않는다. 본문은 없다 | US3-AC4, AC6, US7-AC5 |
| 29 | 세 친구가 모두 쓴 뒤 더 기다린다 | 그날은 더 올라오지 않는다 | US3-AC3 |
| 30 | `AI_COMPANION_POSTING_ENABLED=false`로 다시 띄운다 | 글이 더 올라오지 않는다. 올라간 글은 남아 있고 반응은 그대로 한다 | US3-AC11, US7-AC1 |
| 31 | B가 AI 친구의 글을 신고하고 운영자가 숨긴다 | 사람의 글처럼 접수되고 숨겨진다 | US5-AC6 |

## 운영 준비 (켜기 전에)

1. **여러 친구의 반응**은 009의 스위치(`ai_companion_enabled`)를 따른다. 이미 켜져 있으면 배포와 함께 적용된다. 하루 상한이 45로 오른다. 호출 한도가 걱정되면 `ai_companion_daily_limit`으로 낮춘다.
2. **글쓰기**는 꺼진 채로 나간다. 켜기 전에 아래를 한다.
   - `CompanionPostLiveTest`로 실제 모델의 글 30개를 읽는다. 열에 아홉이 직장인의 고민 글로 자연스럽게 읽히고 FR-025를 어긴 것이 없어야 한다(SC-013).
   - 생성형 AI 고지(인공지능기본법 제31조)를 다시 본다. 지어 쓴 글이 더해졌다. 표시는 글 카드와 글 상세의 "AI 친구", 미리 알리기는 안내 화면이다.
   - terraform 변수 `ai_companion_posting_enabled = true`로 켠다. 비밀 값의 같은 이름 필드로는 켜지지 않는다.
3. 운영 기록을 본다.

   ```sql
   -- 글마다 정해진 수와 결과
   select post_id, target_count, done_count, status, skip_reason from companion_reaction order by id desc limit 20;
   -- 수의 비율
   select target_count, count(*) from companion_reaction where created_at > now() - interval '7 days' group by 1 order by 1;
   -- AI 친구의 글
   select companion_id, day, topic, status, give_up_reason, attempts, post_id from companion_post order by id desc limit 20;
   ```
