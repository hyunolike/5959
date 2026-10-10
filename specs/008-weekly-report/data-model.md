# Data Model: 주간 리포트 (008-weekly-report)

Flyway `V8__weekly_report.sql`. 모든 제약에 이름을 붙인다. 다른 모듈의 테이블에는 FK를 걸지 않는다.

## report 모듈

### `weekly_report`

회원 한 명의 한 주. 발행할 때의 수치와 편지를 가진다.

| 열 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT IDENTITY, PK | |
| `member_id` | BIGINT NOT NULL | |
| `week_start` | DATE NOT NULL | 그 주의 월요일(한국 시간) |
| `post_count` | INT NOT NULL | 쓴 글 수. 1 이상 |
| `emotion_counts` | JSONB NOT NULL | 감정별 글 수. 모든 감정이 키로 있다 |
| `unanalyzed_count` | INT NOT NULL | 분석되지 않은 글 수 |
| `top_emotion` | VARCHAR(20) | 분석된 글이 없으면 NULL |
| `defeated_count` | INT NOT NULL | 그 주에 처치된 내 몬스터 수 |
| `received_likes` | INT NOT NULL | |
| `received_comments` | INT NOT NULL | |
| `letter_status` | VARCHAR(10) NOT NULL | `PENDING`, `DONE`, `GIVEN_UP`, `SUPPORT` |
| `letter` | TEXT | `DONE`일 때만 있다 |
| `letter_attempts` | INT NOT NULL DEFAULT 0 | |
| `letter_next_attempt_at` | TIMESTAMPTZ | `PENDING`일 때만 있다 |
| `letter_last_error` | VARCHAR(100) | 실패의 분류만. 공급자의 답을 담지 않는다 |
| `published_at` | TIMESTAMPTZ NOT NULL | 24시간 기한과 1년 보관의 기준 |

- `CONSTRAINT weekly_report_member_week_key UNIQUE (member_id, week_start)`. 겹치지 않게 하는 것이 이 제약이다(R4). 목록 조회(회원의 리포트를 최신 주부터)도 이 색인이 맡는다.
- `CONSTRAINT weekly_report_letter_status_check CHECK (letter_status IN ('PENDING', 'DONE', 'GIVEN_UP', 'SUPPORT'))`
- `CONSTRAINT weekly_report_letter_check CHECK ((letter_status = 'DONE') = (letter IS NOT NULL))`
- `CONSTRAINT weekly_report_letter_pending_check CHECK ((letter_status = 'PENDING') = (letter_next_attempt_at IS NOT NULL))`
- `CONSTRAINT weekly_report_counts_check CHECK (post_count >= 1 AND unanalyzed_count >= 0 AND defeated_count >= 0 AND received_likes >= 0 AND received_comments >= 0)`
- `CONSTRAINT weekly_report_week_start_check CHECK (EXTRACT(ISODOW FROM week_start) = 1)`. 월요일이 아닌 날짜가 들어오지 않는다.
- `CREATE INDEX weekly_report_letter_pending_idx ON weekly_report (published_at DESC) WHERE letter_status = 'PENDING'`. 편지 재시도가 나중에 발행된 것부터 잡는다.
- `CREATE INDEX weekly_report_published_idx ON weekly_report (published_at)`. 1년 지난 것을 지울 때 쓴다.

본문, 닉네임, 글 번호를 담지 않는다. 글을 지워도 리포트에 남는 것은 숫자뿐이다.

### `weekly_report_run`

어느 주의 리포트 만들기가 끝났는지.

| 열 | 타입 | 설명 |
|---|---|---|
| `week_start` | DATE, PK | |
| `completed_at` | TIMESTAMPTZ NOT NULL | 빠진 회원도 실패한 회원도 없이 한 바퀴를 돈 때 |

행이 있으면 그 주는 다시 훑지 않는다. 행이 없고 그 주가 지난주면 주기 작업이 훑는다.

## notification 모듈 (더하는 것)

- `notification.type`의 CHECK에 `WEEKLY_REPORT`를 더한다.
- `notification.report_week_start DATE`를 더한다. `WEEKLY_REPORT`일 때만 있다.
- 대상 CHECK(`notification_target_check`)를 고친다. 글, 보스, 리포트 주 가운데 종류에 맞는 하나가 있어야 한다.
- 멱등 키는 `WEEKLY_REPORT:<week_start>`다. 받는 회원과 묶여 유일하다(기존 `(receiver_id, dedup_key)` 제약).

## post 모듈 (더하는 것)

- 테이블 변화는 없다. 받은 공감을 때로 세기 위해 `CREATE INDEX post_likes_post_created_idx ON post_likes (post_id, created_at)`을 더한다. 댓글은 `comments (post_id, ...)` 색인이 이미 있는지 구현할 때 확인한다.
- 대상 회원 찾기를 위해 `CREATE INDEX posts_created_author_idx ON posts (created_at, author_id) WHERE deleted_at IS NULL`을 더한다.

## 설정

| 키 | 기본값 | 설명 |
|---|---|---|
| `ogu.report.scheduler-enabled` | true | 리포트 만들기와 편지 재시도의 주기 작업 |
| `ogu.report.poll-interval` | 1m | 주기. e2e는 5s |
| `ogu.report.publish-at` | 05:00 | 월요일 이 시각(한국 시간)부터 만든다. e2e는 00:00 |
| `ogu.report.batch-size` | 200 | 대상 회원을 한 번에 읽는 수 |
| `ogu.report.max-per-tick` | 500 | 한 차례에 만드는 리포트 수의 한도 |
| `ogu.report.letter.initial-interval` | 30s | |
| `ogu.report.letter.max-interval` | 5m | |
| `ogu.report.letter.deadline` | 24h | |
| `ogu.report.letter.max-length` | 300 | 그래핌 기준 |
| `ogu.report.retention` | 365d | |
| `ogu.ai.letter-max-tokens` | 900 | 추론 모델이라 넉넉히 둔다 |

## 이벤트

| 이벤트 | 내는 곳 | 싣는 것 | 받는 곳 |
|---|---|---|---|
| `WeeklyReportPublished` | `report`(리포트를 넣은 트랜잭션) | `memberId`, `weekStart` | `notification`(커밋 뒤, 알림 하나) |

수치와 편지를 싣지 않는다(FR-015).
