# Data Model: AI 친구의 활동 넓히기 (012-companion-activity)

PR마다 Flyway 스크립트가 하나다. 모든 제약에 이름을 붙인다. `companion`의 테이블은 다른 모듈의 테이블에 FK를 걸지 않는다.

## PR 1: `V10__companion_multi_reaction.sql`

### `companion_reaction` (고치는 것)

글 하나에 대해 AI 친구들이 반응하는 과정. 여전히 글 하나에 한 행이다.

| 열 | 타입 | 설명 |
|---|---|---|
| `target_count` | SMALLINT NOT NULL DEFAULT 1 | 이 글에 반응할 AI 친구의 수. 행을 만들 때 한 번 정한다. 이미 있는 행은 1 |
| `done_count` | SMALLINT NOT NULL DEFAULT 0 | 지금까지 반응한 친구의 수. 이미 `DONE`인 행은 1 |

뜻이 바뀌는 열:
- `status`: `DONE`은 한 명 이상 반응하고 끝난 것, `SKIPPED`는 아무도 반응하지 않고 끝난 것이다.
- `skip_reason`: 끝난 까닭. `SKIPPED`면 언제나 있다. `DONE`이면 정해진 수를 다 채우지 못하고 멈췄을 때만 있다.
- `attempts`: 지금 차례인 친구에게 댓글을 청한 횟수. 친구가 반응을 남기면 0으로 돌아간다.
- `companion_id`: 가장 최근에 댓글을 청한 친구.
- `comment_id`: 이 글에 처음 남긴 AI 친구의 댓글. 친구별 댓글은 `companion_reaction_entry`에 있다.

제약:
- `CONSTRAINT companion_reaction_target_count_check CHECK (target_count BETWEEN 1 AND 3)`
- `CONSTRAINT companion_reaction_done_count_check CHECK (done_count BETWEEN 0 AND target_count)`
- `companion_reaction_done_check`를 바꾼다: `CHECK (status = 'WAITING' OR (status = 'DONE') = (done_count > 0))`
- `companion_reaction_skip_check`를 바꾼다: `CHECK ((status <> 'SKIPPED' OR skip_reason IS NOT NULL) AND (status <> 'WAITING' OR skip_reason IS NULL))`
- `companion_reaction_post_key`, `status_check`, `skip_reason_check`, `waiting_check`, `completed_check`는 그대로다.
- `companion_reaction_done_idx`는 지운다. 가장 오래 쉰 친구는 entry로 고른다.

### `companion_reaction_entry` (새로)

한 글에 AI 친구 한 명이 남긴 반응 한 건.

| 열 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT IDENTITY, PK | 가장 오래 쉰 친구를 고를 때 이 번호로 견준다 |
| `reaction_id` | BIGINT NOT NULL | `companion_reaction(id)` |
| `post_id` | BIGINT NOT NULL | |
| `companion_id` | SMALLINT NOT NULL | |
| `comment_id` | BIGINT NOT NULL | 남긴 댓글 |
| `created_at` | TIMESTAMPTZ NOT NULL | |

- `CONSTRAINT companion_reaction_entry_reaction_fkey FOREIGN KEY (reaction_id) REFERENCES companion_reaction (id)` (같은 모듈의 테이블이다)
- `CONSTRAINT companion_reaction_entry_post_companion_key UNIQUE (post_id, companion_id)`. 한 글에 같은 친구는 한 번을 지키는 것이 이 제약이다(research R1).
- `CREATE INDEX companion_reaction_entry_companion_idx ON companion_reaction_entry (companion_id, id DESC)`

**옮기기**: 이미 `DONE`이고 `companion_id`와 `comment_id`가 있는 행마다 entry를 하나 만든다(행의 `id` 순서로 넣어 친구의 차례가 이어진다). 그 행들의 `done_count`를 1로 올린다.

**적기**(한 트랜잭션): entry를 `ON CONFLICT DO NOTHING`으로 넣고, 행의 `done_count`를 그 글의 entry 수로 맞추고, `comment_id`가 비어 있으면 채운다.

**닫기**(한 문장): `status = CASE WHEN done_count > 0 THEN 'DONE' ELSE 'SKIPPED' END`, `skip_reason = :reason`. 정해진 수를 다 채웠을 때는 까닭 없이 `DONE`으로 닫는다.

**상태 전이**

```text
(PostCreated) → WAITING(target_count = 1..3)
  ├─ 친구 한 명이 남김, 남은 수 있음 ─→ WAITING (done_count + 1, attempts = 0, 3~10분 뒤)
  ├─ 친구 한 명이 남김, 다 채움 ─────→ DONE
  ├─ 멈출 까닭이 생김 ────────────────→ done_count > 0이면 DONE(skip_reason), 아니면 SKIPPED(skip_reason)
  ├─ 판정이 끝나지 않음 ──────────────→ WAITING (30초 뒤, attempts 그대로)
  └─ 호출 실패, 검사 탈락 ────────────→ WAITING (attempts + 1) ─ attempts = 3 ─→ 닫는다(FAILED | REJECTED)
```

건너뛴 까닭의 값은 009 그대로다(`HUMAN_COMMENT`, `RISK`, `GONE`, `EXPIRED`, `DAILY_LIMIT`, `FAILED`, `REJECTED`).

### post 모듈 (고치는 것)

테이블 변화는 없다. `PostCompanionApi`의 타입을 바꾼다(research R7).

| 타입 | 필드 |
|---|---|
| `CompanionTarget` | `companionCommentId`를 지우고 `companionComments: List<CompanionComment>`를 더한다 |
| `CompanionComment` | `memberId`, `commentId`, `content`, `visible`. AI 친구가 이 글에 남긴, 지우지 않은 댓글. 회원마다 가장 먼저 남긴 것 하나 |
| `CompanionReactionOutcome` | `FULL`을 더한다. `ALREADY_REACTED`는 이 회원이 이미 남겼다는 뜻으로 좁힌다 |
| `react(...)` | 인자 `limit: Int`를 더한다 |

### ai 모듈 (고치는 것)

`CompanionCommentInput`에 `previous: List<String>`(같은 글에 앞서 남긴 보이는 AI 친구의 댓글)을 더한다. 프롬프트는 `companion-comment-v2.st`다.

### 설정 (PR 1)

| 키 | 기본값 | 설명 |
|---|---|---|
| `ogu.companion.daily-limit` | `${AI_COMPANION_DAILY_LIMIT:45}` | 30에서 올린다 |
| `ogu.companion.count-weights` | `${AI_COMPANION_COUNT_WEIGHTS:60,30,10}` | 1명, 2명, 3명의 무게 |
| `ogu.companion.follow-up.min-gap` | `${AI_COMPANION_FOLLOW_UP_MIN:3m}` | 앞 댓글 뒤 다음 친구까지의 가장 짧은 간격. e2e는 2s |
| `ogu.companion.follow-up.max-gap` | `${AI_COMPANION_FOLLOW_UP_MAX:10m}` | 가장 긴 간격. e2e는 4s |

## PR 2: `V11__companion_posts.sql`

### `posts` (고치는 것, post 모듈)

| 열 | 타입 | 설명 |
|---|---|---|
| `author_kind` | VARCHAR(10) NOT NULL DEFAULT 'HUMAN' | 글을 쓸 때의 글쓴이 종류. `HUMAN`, `AI` |
| `author_job_role` | VARCHAR(20) | NOT NULL을 푼다. AI 친구의 글은 NULL |
| `author_career_year` | VARCHAR(20) | 〃 |

- `CONSTRAINT posts_author_kind_check CHECK (author_kind IN ('HUMAN', 'AI'))`
- `CONSTRAINT posts_author_profile_check CHECK ((author_kind = 'AI') = (author_job_role IS NULL) AND (author_kind = 'AI') = (author_career_year IS NULL))`
- 이미 있는 글은 모두 `HUMAN`이다.

**엔티티와 공개 타입**
- `Post.authorKind: AuthorKind`, `authorJobRole: JobRole?`, `authorCareerYear: CareerYear?`. `Post.writeByAi(authorId, content, commentTone, now)`.
- `PostSummary.authorAi: Boolean`, `authorJobRole: JobRole?`, `authorCareerYear: CareerYear?`.
- `PostCompanionApi`에 `write`, `latestVisiblePostAt`, `findWrittenSince`를 더한다(research R11).
- `PostReportApi.authorIdsBetween`과 `PostApi.visibleIdsSince`는 `author_kind = 'HUMAN'`인 글만 본다(research R18).

### `companion_post` (새로, companion 모듈)

AI 친구가 글을 쓰려 한 일 한 건. 본문을 담지 않는다(FR-037).

| 열 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT IDENTITY, PK | |
| `companion_id` | SMALLINT NOT NULL | 글을 쓰는 친구 |
| `day` | DATE NOT NULL | 행을 만든 때의 한국 시간 날짜 |
| `topic` | VARCHAR(40) NOT NULL | 소재의 열쇠(`PostTopics`) |
| `comment_tone` | VARCHAR(20) NOT NULL | 글에 붙일 댓글 말투 |
| `status` | VARCHAR(10) NOT NULL | `WRITING`, `POSTED`, `GIVEN_UP` |
| `give_up_reason` | VARCHAR(20) | `GIVEN_UP`일 때만 있다 |
| `post_id` | BIGINT | `POSTED`일 때만 있다 |
| `attempts` | INT NOT NULL DEFAULT 0 | AI에 글을 청한 횟수 |
| `next_attempt_at` | TIMESTAMPTZ | `WRITING`일 때만 있다 |
| `last_error` | VARCHAR(100) | 실패의 분류만 |
| `created_at` | TIMESTAMPTZ NOT NULL | |
| `completed_at` | TIMESTAMPTZ | `POSTED`나 `GIVEN_UP`이 된 때 |

- `CONSTRAINT companion_post_companion_day_key UNIQUE (companion_id, day)`. 친구마다 하루 하나를 지킨다(research R12).
- `CREATE UNIQUE INDEX companion_post_writing_key ON companion_post ((1)) WHERE status = 'WRITING'`. 쓰는 중인 행은 하나다.
- `CONSTRAINT companion_post_status_check CHECK (status IN ('WRITING', 'POSTED', 'GIVEN_UP'))`
- `CONSTRAINT companion_post_give_up_reason_check CHECK (give_up_reason IN ('NOT_QUIET', 'OFF_HOURS', 'EXPIRED', 'DAILY_LIMIT', 'FAILED', 'REJECTED'))`
- `CONSTRAINT companion_post_give_up_check CHECK ((status = 'GIVEN_UP') = (give_up_reason IS NOT NULL))`
- `CONSTRAINT companion_post_posted_check CHECK ((status = 'POSTED') = (post_id IS NOT NULL))`
- `CONSTRAINT companion_post_writing_check CHECK ((status = 'WRITING') = (next_attempt_at IS NOT NULL))`
- `CONSTRAINT companion_post_completed_check CHECK ((status = 'WRITING') = (completed_at IS NULL))`
- `CONSTRAINT companion_post_comment_tone_check CHECK (comment_tone IN ('VENT_WITH_ME', 'COMFORT_ME', 'WARM_ADVICE', 'MAKE_ME_LAUGH'))`
- `CREATE INDEX companion_post_day_idx ON companion_post (day)`

**그만둔 까닭**

| 값 | 뜻 | 스펙 |
|---|---|---|
| `NOT_QUIET` | 쓰는 사이에 새 글이 올라왔다 | US3-AC2 |
| `OFF_HOURS` | 쓰는 사이에 시간대를 벗어났다 | US3-AC5 |
| `EXPIRED` | 날짜가 바뀌었다(오래 꺼져 있었다) | Edge Cases |
| `DAILY_LIMIT` | 그날 청한 횟수가 상한에 닿았다 | US7-AC6 |
| `FAILED` | AI가 세 번 답하지 못했다 | US5-AC7 |
| `REJECTED` | AI의 답이 세 번 검사에 걸렸다 | US5-AC1, AC2 |

**상태 전이**

```text
(조건을 채움) → WRITING ──┬─ 글을 올림 ───────────────→ POSTED(post_id)
                          ├─ 조건이 깨짐 ─────────────→ GIVEN_UP(NOT_QUIET | OFF_HOURS | EXPIRED | DAILY_LIMIT)
                          └─ 호출 실패, 검사 탈락 ────→ WRITING (attempts + 1) ─ attempts = 3 ─→ GIVEN_UP(FAILED | REJECTED)
```

### ai 모듈 (더하는 것)

| 타입 | 필드 |
|---|---|
| `CompanionPostWriter` | `write(key, input): String` |
| `CompanionPostInput` | `topic: String`(소재의 한 줄 설명), `tone: CompanionTone`, `voice: CompanionVoice` |
| `CompanionPostFailed` | `kind`. 모델의 답을 담지 않는다 |

### 계약 (PR 2)

`contracts/openapi.yaml` 0.13.0. [contracts/companion-activity.openapi.yaml](contracts/companion-activity.openapi.yaml)에 달라지는 스키마를 먼저 적었다.

| 스키마 | 바뀌는 것 |
|---|---|
| `Author` | `ai: boolean` 필수. `jobRole`, `careerYear`는 null일 수 있다 |
| `PublicFeedItem` | `ai: boolean` 필수. `jobRole`, `careerYear`는 null일 수 있다 |

새 경로와 새 오류 코드는 없다.

### 설정 (PR 2)

| 키 | 기본값 | 설명 |
|---|---|---|
| `ogu.companion.posting.enabled` | `${AI_COMPANION_POSTING_ENABLED:false}` | 꺼져 있으면 글 쓰는 주기 작업이 DB를 읽지 않는다. e2e도 false |
| `ogu.companion.posting.quiet` | `${AI_COMPANION_POSTING_QUIET:6h}` | 이 동안 보이는 새 글이 없어야 쓴다 |
| `ogu.companion.posting.daily-posts` | `${AI_COMPANION_POSTING_DAILY_POSTS:3}` | 하루(한국 시간)에 올리는 글 수 |
| `ogu.companion.posting.daily-requests` | 9 | 하루에 AI에 글을 청하는 횟수. 실패한 시도도 든다 |
| `ogu.companion.posting.max-attempts` | 3 | 글 하나에 청하는 횟수 |
| `ogu.companion.posting.active-from` | `08:00` | 한국 시간. 이때부터 쓴다 |
| `ogu.companion.posting.active-until` | `23:00` | 이때부터는 쓰지 않는다 |
| `ogu.companion.posting.recent-topics` | 10 | 이만큼의 최근 소재는 다시 고르지 않는다 |
| `ogu.companion.posting.poll-interval` | 1m | 주기 |
| `ogu.companion.posting.scheduler-enabled` | true | 테스트가 주기 작업을 끄는 데 쓴다 |
| `ogu.ai.companion-post-max-tokens` | 1200 | |

Resilience4j에 `companionPost` 인스턴스(회로 차단기와 시간 제한)를 더한다.

## 이벤트

새 이벤트는 없다. AI 친구의 글은 `post`가 사람의 글과 같은 `PostCreated`, `PostWritten`을 낸다. 그래서 감정 분석, 키워드 판정과 위험 분류, 임베딩, 반응 기록이 고치지 않아도 따라온다.
