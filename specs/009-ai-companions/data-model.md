# Data Model: AI 친구 (009-ai-companions)

Flyway `V9__ai_companions.sql`. 모든 제약에 이름을 붙인다. `companion`의 테이블은 다른 모듈의 테이블에 FK를 걸지 않는다.

## member 모듈 (더하는 것)

### `member`

| 열 | 타입 | 설명 |
|---|---|---|
| `kind` | VARCHAR(10) NOT NULL DEFAULT 'HUMAN' | `HUMAN`, `AI` |

- `CONSTRAINT member_kind_check CHECK (kind IN ('HUMAN', 'AI'))`
- `member_auth_method_check`를 바꾼다: `auth_method IN ('EMAIL', 'KAKAO', 'GOOGLE', 'NONE')`. `NONE`은 로그인 수단이 없다는 뜻이다.
- `CONSTRAINT member_ai_auth_method_check CHECK ((kind = 'AI') = (auth_method = 'NONE'))`
- `CONSTRAINT member_ai_no_credentials_check CHECK (kind <> 'AI' OR (email IS NULL AND password_hash IS NULL))`
- `member_onboarding_fields_required_check`를 바꾼다. 사람은 지금 그대로이고 AI는 직군과 경력이 없어야 한다.

  ```sql
  CHECK (
    (kind = 'HUMAN' AND (onboarded_at IS NULL
      OR (nickname IS NOT NULL AND nickname_key IS NOT NULL AND job_role IS NOT NULL AND career_year IS NOT NULL)))
    OR
    (kind = 'AI' AND onboarded_at IS NOT NULL AND nickname IS NOT NULL AND nickname_key IS NOT NULL
      AND job_role IS NULL AND career_year IS NULL)
  )
  ```

- `role`은 `MEMBER`다. AI 회원은 운영자가 될 수 없다: `CONSTRAINT member_ai_role_check CHECK (kind <> 'AI' OR role = 'MEMBER')`.

**시드**: AI 회원 셋을 넣는다. `nickname_key`는 닉네임을 소문자로 한 것이다(한글이라 같다).

| nickname | kind | auth_method | job_role | career_year | onboarded_at |
|---|---|---|---|---|---|
| 다정한 오구 | AI | NONE | NULL | NULL | now() |
| 씩씩한 오구 | AI | NONE | NULL | NULL | now() |
| 차분한 오구 | AI | NONE | NULL | NULL | now() |

닉네임에 띄어쓰기가 있어 사람의 닉네임(`^[가-힣A-Za-z0-9]{1,10}$`)과 겹치지 않는다(research R3).

**엔티티와 공개 타입**
- `Member.kind: MemberKind`(읽기 전용, `insertable = false, updatable = false`). 코드로 AI 회원을 만드는 길을 두지 않는다.
- `AuthMethod`에 `NONE`을 더한다.
- `MemberInfo`에 `ai: Boolean`을 더한다.
- `NicknameUnavailableReason`에 `RESERVED`, `ErrorCode`에 `NICKNAME_RESERVED`(400)를 더한다.
- `MemberRepository.existsAiNicknameKeyWithoutSpaces(key)`: `kind = 'AI'`인 회원의 `nickname_key`에서 띄어쓰기를 뺀 것이 `key`와 같은가.

## companion 모듈

### `companion`

AI 친구 한 명과 그 말씨.

| 열 | 타입 | 설명 |
|---|---|---|
| `id` | SMALLINT, PK | 1, 2, 3 |
| `member_id` | BIGINT NOT NULL | AI 회원의 번호 |
| `voice` | VARCHAR(10) NOT NULL | `GENTLE`, `HEARTY`, `CALM` |

- `CONSTRAINT companion_member_key UNIQUE (member_id)`
- `CONSTRAINT companion_voice_key UNIQUE (voice)`
- `CONSTRAINT companion_voice_check CHECK (voice IN ('GENTLE', 'HEARTY', 'CALM'))`
- 시드는 회원 시드와 한 문장이다(`WITH m AS (INSERT INTO member ... RETURNING id, nickname) INSERT INTO companion ...`).

### `companion_reaction`

글 하나에 대한 AI 친구의 반응 한 건. 본문을 담지 않는다(FR-020).

| 열 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT IDENTITY, PK | |
| `post_id` | BIGINT NOT NULL | |
| `post_created_at` | TIMESTAMPTZ NOT NULL | 기다리는 시간과 24시간 기한의 기준 |
| `status` | VARCHAR(10) NOT NULL | `WAITING`, `DONE`, `SKIPPED` |
| `skip_reason` | VARCHAR(20) | `SKIPPED`일 때만 있다 |
| `companion_id` | SMALLINT | 댓글을 청한 친구. 청하기 전에는 NULL |
| `comment_id` | BIGINT | `DONE`일 때만 있다 |
| `attempts` | INT NOT NULL DEFAULT 0 | AI에 댓글을 청한 횟수 |
| `next_attempt_at` | TIMESTAMPTZ | `WAITING`일 때만 있다 |
| `last_error` | VARCHAR(100) | 실패의 분류만. 공급자의 답과 본문을 담지 않는다 |
| `created_at` | TIMESTAMPTZ NOT NULL | |
| `completed_at` | TIMESTAMPTZ | `DONE`이나 `SKIPPED`가 된 때 |

- `CONSTRAINT companion_reaction_post_key UNIQUE (post_id)`. 한 글에 한 번을 지키는 것이 이 제약이다(research R4).
- `CONSTRAINT companion_reaction_status_check CHECK (status IN ('WAITING', 'DONE', 'SKIPPED'))`
- `CONSTRAINT companion_reaction_skip_reason_check CHECK (skip_reason IN ('HUMAN_COMMENT', 'RISK', 'GONE', 'EXPIRED', 'DAILY_LIMIT', 'FAILED', 'REJECTED'))`
- `CONSTRAINT companion_reaction_skip_check CHECK ((status = 'SKIPPED') = (skip_reason IS NOT NULL))`
- `CONSTRAINT companion_reaction_done_check CHECK ((status = 'DONE') = (comment_id IS NOT NULL))`
- `CONSTRAINT companion_reaction_waiting_check CHECK ((status = 'WAITING') = (next_attempt_at IS NOT NULL))`
- `CONSTRAINT companion_reaction_completed_check CHECK ((status = 'WAITING') = (completed_at IS NULL))`
- `CREATE INDEX companion_reaction_due_idx ON companion_reaction (post_created_at) WHERE status = 'WAITING'`. 주기 작업이 먼저 올라온 글부터 맡는다.
- `CREATE INDEX companion_reaction_done_idx ON companion_reaction (companion_id, completed_at DESC) WHERE status = 'DONE'`. 가장 오래 쉰 친구를 고를 때 쓴다.

**건너뛴 까닭**

| 값 | 뜻 | 스펙 |
|---|---|---|
| `HUMAN_COMMENT` | 다른 회원이 먼저 댓글을 달았다 | US1-AC2 |
| `RISK` | 위기나 우려로 판정된 글이다 | US3-AC1 |
| `GONE` | 지웠거나 숨긴 글이다 | US3-AC2 |
| `EXPIRED` | 올라온 지 24시간이 지났다 | FR-015 |
| `DAILY_LIMIT` | 그날의 몫이 떨어졌다 | US5-AC2 |
| `FAILED` | AI가 세 번 답하지 못했다 | FR-013 |
| `REJECTED` | AI의 답이 세 번 검사에 걸렸다 | US3-AC4, US3-AC5 |

**상태 전이**

```text
(PostCreated) → WAITING ──┬─ 댓글과 공감을 남김 ──────────────→ DONE
                          ├─ 대상이 아님(위의 까닭) ────────────→ SKIPPED
                          ├─ 판정이 끝나지 않음 ─→ WAITING (30초 뒤, attempts 그대로)
                          └─ 호출 실패, 검사 탈락 ─→ WAITING (attempts + 1, 간격을 늘려)
                                                    └─ attempts = 3 ─→ SKIPPED(FAILED | REJECTED)
```

`DONE`과 `SKIPPED`에서는 나가지 않는다. 글이 고쳐져도 다시 `WAITING`이 되지 않는다(US1-AC7).

### `companion_daily_usage`

하루에 AI에 댓글을 청한 횟수.

| 열 | 타입 | 설명 |
|---|---|---|
| `day` | DATE, PK | 한국 시간의 날짜 |
| `used` | INT NOT NULL | |

- `CONSTRAINT companion_daily_usage_used_check CHECK (used >= 0)`
- 받기는 한 문장이다. 돌아온 행이 없으면 몫이 없다.

  ```sql
  INSERT INTO companion_daily_usage (day, used) VALUES (:day, 1)
  ON CONFLICT (day) DO UPDATE SET used = companion_daily_usage.used + 1
    WHERE companion_daily_usage.used < :limit
  RETURNING used
  ```

  `:limit`이 0이면 넣지도 않는다(코드에서 먼저 본다).
- 돌려주기(회로가 열려 호출이 나가지 않았을 때): `UPDATE ... SET used = used - 1 WHERE day = :day AND used > 0`.

## post 모듈 (더하는 것)

테이블 변화는 없다. 파사드 `PostCompanionApi`를 더한다(research R5).

| 타입 | 필드 |
|---|---|
| `CompanionTarget` | `postId`, `authorId`, `content`, `commentTone`, `createdAt`, `hidden`, `companionCommented`, `otherCommented` |
| `CompanionReactionResult` | `outcome`(`REACTED`, `ALREADY_REACTED`, `GONE`, `HUMAN_COMMENTED`), `commentId`(앞의 둘일 때만) |

`otherCommented`를 읽는 쿼리는 `comments (post_id, ...)` 색인을 탄다. 구현할 때 실행 계획을 확인한다.

## safety 모듈 (더하는 것)

테이블 변화는 없다. 파사드 `RiskApi`를 더한다(research R7).

| 타입 | 필드 |
|---|---|
| `PostRisk` | `settled`, `level: RiskLevel` |

## 설정

| 키 | 기본값 | 설명 |
|---|---|---|
| `ogu.companion.enabled` | `${AI_COMPANION_ENABLED:false}` | 꺼져 있으면 주기 작업이 아무 행도 맡지 않는다. e2e는 true |
| `ogu.companion.wait` | `${AI_COMPANION_WAIT:10m}` | 올라온 뒤 기다리는 시간. e2e는 5s |
| `ogu.companion.max-age` | 24h | 이보다 오래된 글에는 반응하지 않는다 |
| `ogu.companion.daily-limit` | `${AI_COMPANION_DAILY_LIMIT:30}` | 하루(한국 시간)에 AI에 댓글을 청하는 횟수 |
| `ogu.companion.max-attempts` | 3 | 한 글에 댓글을 청하는 횟수 |
| `ogu.companion.poll-interval` | 30s | 주기. e2e는 2s |
| `ogu.companion.batch-size` | 3 | 한 차례에 맡는 행 수. `@Scheduled` 스레드가 둘뿐이라 작게 둔다 |
| `ogu.companion.retry.initial-interval` | 1m | |
| `ogu.companion.retry.max-interval` | 5m | |
| `ogu.companion.risk-wait` | 30s | 판정이 끝나지 않았을 때 다시 보는 간격 |
| `ogu.companion.scheduler-enabled` | true | 테스트가 주기 작업을 끄는 데 쓴다 |
| `ogu.ai.companion-max-tokens` | 900 | 추론 모델이라 넉넉히 둔다 |
| `ogu.ai.companion-temperature` | 0.8 | |

Resilience4j에 `companionComment` 인스턴스(회로 차단기와 시간 제한)를 더한다.

## 이벤트

새 이벤트는 없다. `companion`은 `post.PostCreated`를 커밋 뒤에 받아 행을 넣는다. 댓글과 공감은 `post`가 지금 내는 `CommentCreated`, `CommentWritten`, `PostLiked`로 나간다.
