# Data Model: 회원 탈퇴 (013-member-withdrawal)

마이그레이션은 `V13__member_withdrawal.sql` 하나다. 올리기 직전에 `origin/main`의 번호를 다시 본다. 새 테이블은 없다.

## 바뀌는 것

### `member`

| 열 | 형 | 설명 |
|---|---|---|
| `withdrawn_at` | `TIMESTAMPTZ` NULL | 탈퇴한 때. NULL이면 탈퇴하지 않았다 |

제약:

- `member_email_password_required_for_email_check`를 다시 건다: `withdrawn_at IS NOT NULL OR auth_method <> 'EMAIL' OR (email IS NOT NULL AND password_hash IS NOT NULL)`. `auth_method`는 가입한 방법의 기록으로 남는다(바꿀 수 없는 열이다).
- `member_withdrawn_erased_check`(새로): `withdrawn_at IS NULL OR (email IS NULL AND password_hash IS NULL AND nickname IS NULL AND nickname_key IS NULL AND job_role IS NULL AND career_year IS NULL AND onboarded_at IS NULL AND kind = 'HUMAN' AND role = 'MEMBER')`. 탈퇴한 행에 개인 정보가 남지 않고, AI 친구와 운영자는 탈퇴한 행이 될 수 없다는 것을 DB가 지킨다.
- `member_onboarding_fields_required_check`는 그대로다. `onboarded_at`을 NULL로 바꾸므로 사람 회원의 조건을 통과한다.

### `comments`

| 색인 | 설명 |
|---|---|
| `comments_author_id_idx (author_id, id)` | 탈퇴한 회원의 댓글을 지운 것까지 번호 순으로 맡는다. 지금의 `comments_author_live_idx`는 살아 있는 댓글만 담는다 |

글은 `posts_author_created_at_idx (author_id, created_at)`가 이미 있다.

## 상태

```text
회원(사람)
  가입 ──▶ 온보딩 전 ──▶ 온보딩 마침
              │               │
              └── 탈퇴 ◀──────┘   withdrawn_at 적음. 되돌아가는 길은 없다
```

탈퇴는 한 트랜잭션에서 아래가 함께 일어난다.

| 순서 | 어디 | 무엇 |
|---|---|---|
| 1 | `member` | 회원 행을 `FOR UPDATE`로 잠근다. 운영자나 AI 친구면 거절한다. 이미 탈퇴했으면 아무것도 하지 않는다 |
| 2 | `member` | 개인 정보 열을 NULL로, `withdrawn_at`을 지금으로 |
| 3 | `member` | `oauth_identity`, `auth_session`, `sse_ticket`의 그 회원 행을 지운다 |
| 4 | `member` | `MemberWithdrawn(memberId, withdrawnAt)`을 낸다 |
| 5 | `post` (동기) | 그 회원의 살아 있는 글에 `deleted_at` |
| 6 | `post` (동기) | 그 회원의 살아 있는 댓글과, 그 회원의 원 댓글 아래 살아 있는 답글에 `deleted_at`. 살아 있는 글마다 댓글 수를 지운 만큼 줄인다 |
| 7 | `post` (동기) | 5에서 지운 글들의 글 잠금(`PostLock`)을 한 문장으로 잡는다 |

5에 앞서 `post`는 탈퇴 전용 잠금 하나(`pg_advisory_xact_lock`)를 잡는다. 탈퇴끼리 줄을 세워, 서로의 글에 댓글을 단 두 회원이 함께 탈퇴해도 교착이 없다.

커밋 뒤에는 모듈마다 따로, 실패하면 다시 돈다.

| 어디 | 무엇 | 다시 해도 같은 까닭 |
|---|---|---|
| `post` | 5~7을 한 번 더(겹쳐 들어온 것). 글과 댓글을 200개씩 맡아 `PostRemoved`, `CommentRemoved`를 내고 `content = ''` | `content <> ''`인 것만 맡는다. 5~6은 `deleted_at is null`인 것만 잡는다 |
| `notification` | `notification`의 `receiver_id` 행, `notification_sequence`의 행을 지운다. `{memberId}:x` 신호로 열린 연결을 닫는다 | 지울 것이 없으면 0행이다 |
| `report` | `weekly_report`의 `member_id` 행을 지운다 | 위와 같다 |
| `safety` (이벤트를 따라) | `PostRemoved`, `CommentRemoved`를 받아 열린 신고와 재검토 요청을 닫는다 | 지금의 `RemovalListener` 그대로다 |
| `recommend` (이벤트를 따라) | `PostRemoved`를 받아 `post_embedding`의 행을 지운다 | 지금의 리스너 그대로다 |
| `emotion` (이벤트를 따라) | `PostRemoved`를 받아 `emotion_analysis.reason`을 NULL로 바꾼다(새 리스너). 회원이 스스로 지운 글에도 똑같이 돈다 | 이미 NULL이면 0행이다 |

## 건드리지 않는 것

| 테이블 | 까닭 |
|---|---|
| `post_likes`, `comment_likes` | 다른 회원의 글의 공감 수를 지킨다(FR-009). 행은 번호만 가리킨다 |
| `monsters`, `monster_hp_log` | 줄어든 HP와 처치 기록을 지킨다. 탈퇴한 회원의 글의 몬스터도 행은 남고, 글이 지워져 어디에도 보이지 않는다 |
| `raid_boss`, `raid_contribution`, Redis의 레이드 키 | 줄어든 HP와 참여자 수를 지킨다(006) |
| `emotion_analysis`의 감정과 강도 | 몬스터와 통계가 쓴다. 글의 내용이 들어 있지 않다. 근거 한 줄(`reason`)은 글에서 나온 문장이라 지운다(아래) |
| `risk_assessment`, `report`, `review_request`, `moderation_action` | 판정한 본문과 닉네임이 없다. 1년 보관을 그대로 따른다(FR-016). 열린 것은 `RemovalListener`가 닫는다. 신고자가 적은 설명(`report.detail`, 200자)은 남는다. 운영자가 그 신고를 처리하려면 필요하고 운영자만 본다(spec Assumptions) |
| `like_notification_participant` | 공감 알림을 한 번만 보내려는 표지다. 번호만 있다 |
| `companion_reaction`, `companion_reaction_entry` | 글이 지워지면 차례에 `GONE`으로 닫힌다 |
| `login_attempt` | 이메일과 IP의 SHA-256만 있다. 지금의 정리 주기에 사라진다 |
| `event_publication` | `MemberWithdrawn`에는 회원 번호와 때만 든다. 끝난 발행은 7일 뒤에 지워진다 |

## 공개 타입

| 모듈 | 더하는 것 |
|---|---|
| `member` | 이벤트 `MemberWithdrawn(memberId: Long, withdrawnAt: Instant)`. `MemberInfo.withdrawn: Boolean`. `MemberApi.isWithdrawn(memberId): Boolean`(엔티티가 아니라 값을 읽는다, research R5) |
| `shared` | `PostLock.lockAll(postIds)`. `ErrorCode.OPERATOR_CANNOT_WITHDRAW`(409) |
| `safety` | 없음. HTTP 조회 하나를 더한다 |

모듈의 의존 방향은 바뀌지 않는다. `post`, `notification`, `report`가 `member`의 이벤트를 받는 것은 지금의 허용 표 안이다.
