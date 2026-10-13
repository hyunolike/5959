# Quickstart: AI 친구 검증 (009-ai-companions)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/ai-companions.openapi.yaml](contracts/ai-companions.openapi.yaml), 데이터는 [data-model.md](data-model.md), 결정 근거는 [research.md](research.md)를 본다.

## 준비

```bash
# e2e 프로필은 가짜 댓글 쓰기를 쓰고, 기다리는 시간 5초, 주기 2초로 돈다
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e ./gradlew bootRun
pnpm --filter web dev
```

가짜 댓글 쓰기는 글의 본문에 든 표지로 결과가 정해진다. 표지가 없으면 말투와 말씨의 이름이 든 정해진 댓글을 준다.

| 본문의 표지 | 결과 |
|---|---|
| `[AI댓글실패]` | 계속 실패 |
| `[AI댓글실패:2]` | 두 번 실패한 뒤 성공 |
| `[AI댓글욕설]` | 욕설 목록의 낱말이 든 댓글 |
| `[AI댓글위험]` | 위험 분류에 걸리는 댓글 |
| `[AI댓글긴글]` | 300자를 넘는 댓글 |

글의 위험 판정은 지금의 표지(`[위기]`, `[우려]`, `[위험분류실패]`)를 그대로 쓴다.

## 자동 검증

```bash
cd apps/api && ./gradlew test
pnpm --filter web test
pnpm --filter web test:e2e:full          # ai-companions.spec.ts
# 실제 모델이 쓴 댓글 40개(말투 넷에 글 열 개씩)를 출력한다(키가 있을 때만). 사람이 읽는다
OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*CompanionCommentLiveTest*" -i | grep LIVE
```

## 수동 검증 시나리오 (로컬)

회원 A, B가 가입과 온보딩을 마친 상태에서 시작한다. "기다린다"는 e2e 프로필에서 10초쯤이다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | A가 `[불안:낮음]` 글을 쓰고 기다린다 | AI 친구의 댓글 하나와 공감 하나. 몬스터 HP가 4 줄었다 | US1-AC1, US1-AC5 |
| 2 | A의 알림을 본다 | 댓글 알림과 공감 알림. 문구에 "AI 친구"가 있다. 토스트도 같다 | US1-AC6, US2-AC2 |
| 3 | A가 글을 쓰고 5초 안에 B가 댓글을 단다 | AI 친구의 반응이 없다 | US1-AC2 |
| 4 | A가 글을 쓰고 B가 공감만 누른다 | AI 친구가 반응한다 | US1-AC3 |
| 5 | A가 글을 쓰고 자기 글에 댓글을 단다 | AI 친구가 반응한다 | US1-AC4 |
| 6 | 1번의 글을 A가 고치고 기다린다 | AI 친구의 댓글이 늘지 않는다 | US1-AC7 |
| 7 | A가 `[실패]` 글(감정 분석이 끝나지 않음)을 쓰고 기다린다 | 댓글과 공감은 보인다. 몬스터는 아직 없다 | US1-AC8 |
| 8 | B가 AI 친구의 댓글에 공감하고 답글을 단다 | 사람의 댓글처럼 된다. AI 친구는 답하지 않는다. AI 회원에게 알림 행이 생기지 않는다(`select count(*) from notification where receiver_id in (select id from member where kind = 'AI')` = 0) | US1-AC9, FR-017 |
| 9 | 1번의 글을 B로 연다 | 닉네임 옆에 "AI 친구" 글자. 직군과 경력은 없다. 화면 낭독기가 "AI 친구"를 읽는다 | US2-AC1, US2-AC3 |
| 10 | 댓글의 "AI 친구"를 누른다 | AI 친구 안내 화면. AI가 쓴 글, 반응 없는 글에 먼저 답함, 답글에는 답하지 못함, 전문 상담이 아님 | US2-AC4 |
| 11 | 프로필 수정에서 닉네임을 `다정한오구`, `오구AI`, `ai오구`, `AI`로 바꿔 본다. `Kai`도 해 본다 | 앞의 넷은 쓸 수 없다고 안내. `Kai`는 된다 | US2-AC5 |
| 12 | A가 `[위기]` 글, `[우려]` 글을 쓰고 기다린다 | 둘 다 반응이 없다. `companion_reaction.skip_reason`이 `GONE`(위기는 숨겨진다), `RISK` | US3-AC1 |
| 13 | A가 글을 쓰고 5초 안에 지운다. 다른 글은 운영자가 숨긴다 | 반응이 없다. `GONE` | US3-AC2 |
| 14 | A가 `[위험분류실패:2]` 글을 쓴다 | 판정이 끝날 때까지 반응이 없고, 끝난 뒤에 반응한다 | US3-AC3 |
| 15 | A가 `[AI댓글욕설]`, `[AI댓글위험]`, `[AI댓글긴글]` 글을 쓴다 | 셋 다 댓글이 없다. `REJECTED`, `attempts = 3` | US3-AC4, US3-AC5 |
| 16 | AI 친구가 반응한 글을 A가 위기 표현으로 고친다 | B에게 글과 AI 친구의 댓글이 보이지 않는다 | US3-AC6 |
| 17 | B가 AI 친구의 댓글을 신고하고 운영자가 숨긴다 | 사람의 댓글처럼 접수되고 숨겨진다 | US3-AC7 |
| 18 | 말투 네 가지로 글을 하나씩 쓴다 | 댓글에 그 말투의 이름이 들어 있다(가짜 쓰기) | US4-AC1 |
| 19 | 글 여섯 개를 차례로 쓴다 | 세 친구가 두 번씩 나온다. 한 글에는 한 명 | US4-AC2, US4-AC3 |
| 20 | `AI_COMPANION_ENABLED=false`로 다시 띄우고 글을 쓴다 | 반응이 없다. 앞의 댓글은 그대로다 | US5-AC1 |
| 21 | `AI_COMPANION_DAILY_LIMIT=2`로 띄우고(`delete from companion_daily_usage`) 글 셋을 쓴다 | 둘에만 반응한다. 셋째는 `DAILY_LIMIT` | US5-AC2 |
| 22 | `AI_COMPANION_WAIT=30s`로 띄우고 글을 쓴다 | 30초가 지난 뒤에 반응한다 | US5-AC3 |
| 23 | 끈 채로 글 둘을 쓰고 하나의 `companion_reaction.post_created_at`을 25시간 앞으로 옮긴 뒤 켠다 | 옮기지 않은 글에만 반응한다. 옮긴 글은 `EXPIRED` | US5-AC4 |
| 24 | `select post_id, status, skip_reason, companion_id, attempts, last_error from companion_reaction order by id desc limit 20` | 누가 어느 글에 반응했는지, 건너뛴 까닭이 보인다. 본문이 든 열이 없다 | US5-AC5 |

`US3-AC8`(대신 욕해주기에서 욕설을 쓰지 않는다)과 `US4-AC4`(한국어, 300자)는 실제 모델의 출력(`CompanionCommentLiveTest`)을 읽어 확인한다. 한 번만 반응하는 것(FR-002), 몫을 넘지 않는 것(SC-007), 공급자에 보내는 내용(FR-012), 로그에 본문이 없는 것(FR-020)은 자동 테스트가 본다.

## 측정 (SC-001, SC-006)

- SC-001: 글 20개를 쓰고 댓글이 달리기까지의 시간을 잰다(운영 설정 그대로: 기다리는 시간 10분, 주기 30초). 15분 안에 19개 이상이어야 한다.
- SC-006: 가짜 쓰기가 20초씩 걸리게 한 채 글 쓰기와 댓글 쓰기 100번의 p50, p95를 재고, 기능을 끈 때와 견준다.

결과는 구현이 끝난 뒤 여기에 적는다.

## 운영 준비 (저장소 소유자)

기능은 꺼진 채로 나간다. 아래를 본 뒤 서버의 환경 변수 `AI_COMPANION_ENABLED=true`로 켠다.

1. **이름과 말씨를 확인한다.** 다정한 오구, 씩씩한 오구, 차분한 오구다(research R3). 바꾸려면 합치기 전에 마이그레이션의 시드를 고친다. 합친 뒤에는 `update member set nickname = ..., nickname_key = ... where kind = 'AI'`다.
2. **실제 모델이 쓴 댓글 40개를 읽는다**(`CompanionCommentLiveTest`의 출력). 말투에 맞는지, 진단이나 지어낸 경험이 없는지 본다(SC-008).
3. **생성형 AI 고지를 확인한다.** 인공지능기본법 제31조와 과기정통부의 투명성 확보 가이드라인에 견주어, 댓글의 "AI 친구" 표시와 안내 화면이 충분한지 본다(research R13).
4. **호출 한도를 본다.** 하루 최대 90번(댓글 30개에 세 번씩)이 지금의 감정 분석, 위험 분류와 함께 공급자의 한도 안인지 본다. 모자라면 `AI_COMPANION_DAILY_LIMIT`을 낮춘다.
5. 켠 뒤 하루가 지나면 `select status, skip_reason, count(*) from companion_reaction where created_at > now() - interval '1 day' group by 1, 2`와 `select * from companion_daily_usage order by day desc limit 7`로 본다.
6. 문제가 보이면 `AI_COMPANION_ENABLED=false`로 다시 띄운다. 남은 댓글은 신고와 운영자의 숨김으로 다룬다.
