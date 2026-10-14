# Research: 회원 탈퇴 (013-member-withdrawal)

## R1. 회원 행은 남기고 비운다

**결정**: `member` 행을 지우지 않는다. `withdrawn_at`을 적고 이메일, 비밀번호 해시, 닉네임, 닉네임 키, 직군, 경력, `onboarded_at`을 NULL로 바꾼다. `oauth_identity`, `auth_session`, `sse_ticket`의 그 회원 행은 지운다.

- `posts`, `comments`, `post_likes`, `comment_likes`가 `member(id)`에 FK를 걸고 있고, `monster_hp_log`, `raid_contribution`, `risk_assessment`, `report`, `moderation_action`이 번호로 가리킨다. 행을 지우려면 이것들을 모두 지우거나 고쳐야 하고, 그러면 다른 회원의 공감 수와 HP가 흔들린다(FR-009).
- 비운 행에는 개인 정보가 없다. 번호 하나와 탈퇴한 때만 남는다.
- 닉네임 키가 NULL이라 유일 제약에서 빠지고, 이메일이 NULL이라 `member_email_key`에서 빠진다. 같은 이메일과 닉네임이 바로 다시 쓰인다(FR-013). `oauth_identity`가 지워져 같은 소셜 계정은 새 회원으로 가입된다.
- 세션은 무효로 표시하지 않고 지운다. `SessionCheckFilter`가 요청마다 세션 행을 읽으므로 행이 없으면 이미 받은 access 토큰도 401이다(FR-004). 무효 까닭(`revoke_reason`)의 제약을 넓히지 않아도 된다.
- 제약: `member_email_password_required_for_email_check`는 탈퇴한 행을 빼도록 고치고, `member_withdrawn_erased_check`로 "탈퇴했으면 개인 정보가 모두 비어 있다"를 DB가 지키게 한다.

**버린 것**: 행을 지우고 참조를 "탈퇴 회원" 한 명에게 몰아 주기. `post_likes_pkey (post_id, member_id)`와 `monster_hp_log_attack_key`가 겹쳐 행을 합쳐야 하고, 합치면 "한 사람이 한 번"이라는 기록의 뜻이 깨진다.

## R2. 탈퇴는 이벤트 하나로 알리고, 보이지 않게 하는 일은 같은 트랜잭션에서 한다

**결정**: `member`가 `MemberWithdrawn(memberId, withdrawnAt)`을 낸다. `post`가 이것을 동기 `@EventListener`로 받아 그 회원의 글과 댓글을 같은 트랜잭션에서 지운 것으로 바꾼다. 나머지 정리는 커밋 뒤에 `@ApplicationModuleListener`로 돈다.

| 받는 모듈 | 언제 | 하는 일 |
|---|---|---|
| `post` | 같은 트랜잭션 | 글과 댓글에 `deleted_at`, 내 원 댓글의 답글에 `deleted_at`, 글마다 댓글 수 조정, 글 잠금 |
| `post` | 커밋 뒤 | 늦게 들어온 글과 댓글을 한 번 더 지움, `PostRemoved`와 `CommentRemoved`를 내고 본문을 비움(R4) |
| `notification` | 커밋 뒤 | 받은 알림과 번호 행을 지우고 열린 실시간 연결을 닫음 |
| `report` | 커밋 뒤 | 주간 리포트와 편지를 지움 |

- 의존 방향이 맞는다. `post`, `notification`, `report`는 이미 `member`에 의존한다. `member`는 누구에게도 의존하지 않으므로 `member`가 다른 모듈을 부르는 길은 없고, 이벤트가 유일한 길이다.
- 글과 댓글이 보이지 않게 되는 것이 탈퇴와 함께 커밋된다(FR-007, US2-AC7). 나눠 두면 "로그아웃은 됐는데 내 글이 아직 보인다"는 틈이 생긴다. 고민 글이라 그 틈을 두지 않는다. 공격 반영(`AttackListener`)과 키워드 판정(`ScreeningListener`)이 같은 까닭으로 동기다.
- 같은 트랜잭션에서 하는 일은 문장 몇 개다. 글과 댓글의 수와 상관없이 UPDATE 세 문장과 잠금 한 문장이다(SC-006).
- 커밋 뒤의 정리는 Event Publication Registry에 남아, 실패하면 `EventPublicationResubmitter`가 다시 보내고 재시작 때도 다시 나간다(FR-017, US5).

**버린 것**: (가) 모두 커밋 뒤에 하기. 위의 틈이 생긴다. (나) 글마다 지금의 `PostService.delete`를 부르기. 글 1,000개면 같은 트랜잭션에서 이벤트와 쿼리가 수천 번 돈다. (다) 읽는 쿼리마다 `member.withdrawn_at`을 조인하기. `Visibility` 조건이 쓰이는 곳이 수십 군데이고 하나를 놓치면 샌다.

## R3. 지운 글과 똑같이 다룬다는 것은 같은 표지와 같은 이벤트를 쓴다는 뜻이다

**결정**: 탈퇴는 새 상태를 만들지 않는다. `deleted_at`을 적고, `PostRemoved`와 `CommentRemoved`를 낸다. 피드, 추천, 도감, 통계, 알림 미리보기, 레이드의 감정 집계, 감정 분석과 임베딩의 재시도, AI 친구의 반응은 고치지 않아도 지운 글로 본다.

- 확인한 것: `AnalysisStore.claimLocked`와 `EmbeddingPipeline.claimLocked`는 `PostApi.find`가 null이면 본문 없이 닫는다. `PostModerationApi.contentOf`는 지운 대상에 null을 준다. `ReactionEligibility`는 지운 글을 `GONE`으로 닫는다. `PostReportApi.authorIdsBetween`은 지운 글을 세지 않아 탈퇴한 회원은 주간 리포트의 대상이 아니다(US2-AC9, US2-AC10).
- `safety`의 `RemovalListener`가 `PostRemoved`, `CommentRemoved`를 받아 열린 신고와 재검토 요청을 닫는다. `recommend`가 `PostRemoved`를 받아 임베딩을 지운다(FR-008, FR-015). 이 둘을 위해 탈퇴 전용 길을 따로 만들지 않는다.
- 댓글은 지금의 지우기 규칙(003 FR-014)과 같다. 내 원 댓글의 답글은 함께 지워지고, 댓글 수는 실제로 지운 만큼 준다. 지운 글의 댓글 수는 고치지 않는다(`addCommentCount`가 그렇다).

## R4. 본문은 커밋 뒤에 나눠서 비운다

**결정**: `post`의 커밋 뒤 리스너가 그 회원의 글과 댓글을 200개씩 맡아, 묶음마다 `PostRemoved`나 `CommentRemoved`를 내고 `content`를 빈 글자열로 바꾼다. 리스너는 `Propagation.NOT_SUPPORTED`이고 묶음마다 트랜잭션이 따로다.

- "아직 비우지 않은 것"의 표지는 `content <> ''`이다. 본문은 글이 1자 이상, 댓글이 1~300자라 빈 본문은 비운 것뿐이다. 열을 더하지 않는다.
- 이어 하기가 저절로 된다. 다시 불리면 남은 것만 맡는다. 묶음이 반쯤 가다 죽으면 그 묶음은 통째로 되돌아가 다시 맡는다(US5-AC2, AC3).
- 이벤트를 다시 내도 결과가 같다. 신고 닫기와 임베딩 지우기는 여러 번 해도 같다(US5-AC4). 댓글 수는 같은 트랜잭션의 한 문장에서만 줄고, 그 문장은 `deleted_at is null`인 행만 잡으므로 두 번 줄지 않는다.
- 회원이 탈퇴 전에 스스로 지운 글과 댓글의 본문도 여기서 함께 비운다. 지운 뒤에도 본문은 저장돼 있었다.
- 내 원 댓글 아래의 다른 회원의 답글은 함께 지워졌지만 본문은 그 회원의 것이라 비우지 않는다. 그 답글에 걸린 신고는 닫아야 하므로 `CommentRemoved`는 답글에도 낸다.
- 묶음은 `FOR UPDATE`로 맡는다. 같은 발행이 겹쳐 돌면 뒤에 온 쪽이 기다렸다가, 그사이 비워진 행은 빼고 받는다. 남은 것이 없을 때만 끝난다. `SKIP LOCKED`로 건너뛰면 다른 쪽이 죽었을 때 남은 본문을 두고 끝난 것으로 적힌다.
- 맡는 것은 지운 행(`deleted_at is not null`)뿐이다. 살아 있는 글의 본문을 비우는 길을 두지 않는다.
- 감정 분석의 근거 한 줄(`emotion_analysis.reason`)도 글에서 나온 문장이다. `emotion`이 `PostRemoved`를 받아 지운다. 분석하는 사이에 글이 지워졌으면 결과를 적을 때 근거를 남기지 않는다.

**버린 것**: 같은 트랜잭션에서 본문까지 비우기. 글 1,000개와 댓글 5,000개면 이벤트가 6,000번 나가고 응답이 늦는다(SC-006). 보이지 않게 하는 것만 먼저 하면 회원이 기다릴 까닭이 없다.

## R5. 탈퇴와 겹쳐 들어온 글과 댓글

**결정**: 두 겹으로 막는다. 글과 댓글을 저장한 직후에 `MemberApi.isWithdrawn(authorId)`을 물어 탈퇴했으면 401로 되돌린다. 커밋 뒤 정리도 맨 처음에 "살아 있는 글과 댓글 지우기"를 한 번 더 돈다.

- 탈퇴는 회원 행을 `FOR UPDATE`로 잠근다. 글과 댓글의 INSERT는 FK 때문에 같은 행에 `FOR KEY SHARE`를 잡으므로 둘은 줄을 선다. JPA의 쓰기 잠금(`PESSIMISTIC_WRITE`)은 PostgreSQL에서 `FOR NO KEY UPDATE`라 `FOR KEY SHARE`와 부딪히지 않는다. 그래서 잠금은 네이티브 쿼리로 건다.
  - 글쓰기가 먼저면 탈퇴가 그 커밋을 기다렸다가 방금 올라온 글까지 지운다.
  - 탈퇴가 먼저면 글쓰기의 INSERT가 기다렸다가 통과한다. 이때 저장 직후의 확인이 커밋된 탈퇴를 보고 되돌린다.
- 확인은 엔티티가 아니라 값 하나를 읽는 쿼리다. 같은 트랜잭션에서 이미 읽은 회원 엔티티는 탈퇴 전의 모습이라 그것으로는 알 수 없다.
- 공감과 레이드 공격은 막지 않는다. 겹쳐 들어온 공감 하나는 숫자일 뿐이고 FR-009가 숫자를 남기기로 했다.
- 로그인도 같은 방법으로 막는다. `SessionService.issue`가 세션을 저장한 직후에 탈퇴를 본다. 비밀번호를 견주는 동안 탈퇴가 커밋되면, 그러지 않을 때 탈퇴한 회원에게 살아 있는 세션이 남는다.
- 탈퇴끼리는 `post`가 잠금 하나로 줄을 세운다. 여러 글과 댓글의 행을 한 문장이 잡는 순서는 정해져 있지 않다.
- 고치기는 행을 `FOR UPDATE`로 읽으므로 탈퇴가 먼저면 지운 글이라 404다.

## R6. 운영자와 AI 친구

**결정**: 회원 행을 잠근 뒤 `role = OPERATOR`이면 `409 OPERATOR_CANNOT_WITHDRAW`다. AI 친구는 로그인할 수 없어 이 요청에 닿지 못하지만, 서비스도 `kind = AI`면 거절한다.

- 운영자 지정은 SQL로 한다(005 research R10). 푸는 것도 같다. 화면을 만들지 않는다.
- `moderation_action.operator_id`는 그대로 남는다. 운영자였던 회원이 지정을 풀고 탈퇴해도 처리 기록은 보관 기간 동안 남는다(FR-016).

## R7. 알림: 받는 쪽과 행동한 쪽

**결정**: `NotificationWriter`가 받는 회원이 AI 친구이거나 탈퇴한 회원이면 쓰지 않는다. `MemberInfo`에 `withdrawn`을 더한다. 행동한 회원이 탈퇴했으면 닉네임이 없어 `NotificationViewAssembler`가 지금처럼 `actor`를 null로 주고, 웹은 이미 "누군가"로 보인다.

- 쓰는 곳 한 군데에서 거르므로 댓글, 공감, 몬스터, 레이드, 주간 리포트 알림이 모두 따라온다(FR-011). 009가 AI 친구에게 쓴 방법과 같다.
- 웹의 `UNKNOWN_ACTOR`("누군가")는 004가 탈퇴를 내다보고 넣어 둔 말이다. 새 문구와 새 응답 필드를 더하지 않는다(FR-010).
- 받은 알림은 `receiver_id`로 한 문장에 지운다. 보관 기간이 90일이라 수가 많지 않다. `notification_sequence`의 행도 지운다.
- 알려진 틈: 알림 리스너가 "탈퇴하지 않았다"를 읽은 직후에 탈퇴와 정리가 끝나면 그 회원 앞으로 알림 한 줄이 남을 수 있다. 번호와 종류뿐이고 본문이 없으며 읽을 사람이 없다. 90일 정리에 지워진다. 주간 리포트도 같은 틈이 있고 1년 정리에 지워진다. 잠금을 더해 막을 만큼의 값이 없다고 보았다.
- 열린 실시간 연결은 신호 `{memberId}:x`로 닫는다. 그 회원의 연결이 다른 인스턴스에 있어도 닫힌다. Redis가 내려가 있으면 연결의 수명(15분)이 지나며 닫히고, 그 사이에 갈 알림은 없다(US1-AC6).

## R8. 정리가 끝났는지는 발행 기록으로 본다

**결정**: `GET /api/v1/operator/withdrawals/cleanup`이 `event_publication`에서 끝나지 않은 `MemberWithdrawn` 발행을 세어 준다. 탈퇴 건수와 가장 오래된 것의 때를 준다.

- 정리의 진행을 적는 테이블을 따로 만들지 않는다. 리스너마다 끝났는지는 이미 발행 기록에 있다.
- 열 번 실패한 발행은 다시 보내지 않는다(`ogu.events.resubmit.max-attempts`). 그때부터 이 숫자가 줄지 않으므로 운영자가 알아챈다(FR-018, US5-AC6). 고친 뒤 다시 보내는 방법은 지금의 운영 절차와 같다(재시작하거나 `completion_attempts`를 되돌린다).
- 발행 기록에는 회원 번호와 때만 든다. 끝난 발행은 7일 뒤에 지워진다.

**버린 것**: "없는 것을 채우는" 주기 작업(주간 리포트의 방식). 정리할 것이 세 모듈에 흩어져 있고 `member`는 그 모듈들을 부를 수 없다. 모듈마다 탈퇴한 회원을 훑는 주기 작업을 두면 세 벌이 된다.

## R9. 확인은 요청의 값으로 받는다

**결정**: `POST /api/v1/members/me/withdrawal`에 `{"confirmed": true}`를 보낸다. 없거나 false면 `400 INVALID_REQUEST`다. 성공하면 `204`다.

- `DELETE /api/v1/members/me`는 본문을 싣기 어렵고, 프록시와 클라이언트가 재시도하거나 본문을 떨어뜨릴 수 있다. 되돌릴 수 없는 일이라 뜻을 값으로 받는다(FR-003).
- 온보딩 전의 토큰으로도 부를 수 있다(`SecurityPaths.ONBOARDING_ALLOWED`). 가입만 하고 그만두는 회원이 있다(US1-AC9).
- 웹은 전용 BFF 라우트(`/api/auth/withdraw`)를 둔다. 성공했을 때만 쿠키를 지운다. 로그아웃 라우트는 API가 실패해도 쿠키를 지우지만, 탈퇴는 실패했으면 로그인한 채 남아 다시 해 볼 수 있어야 한다(US1-AC8).
- 탈퇴한 계정으로 로그인하면 이메일이 없어 지금의 "없는 계정" 길을 그대로 탄다. 응답을 따로 만들지 않는다(FR-005).

## R10. 도움받을 곳을 보일지는 `safety`가 답한다

**결정**: `GET /api/v1/safety/withdrawal-notice`가 `{ "recentCrisis": boolean }`을 준다. 최근 30일에 그 회원의 글이나 댓글이 위기로 판정된 적이 있으면 true다. 웹은 true일 때 지금의 도움받을 곳 목록(`/api/v1/safety/support-resources`)을 탈퇴 화면에 함께 보인다.

- 판정 기록은 `safety`의 것이다. `member`가 읽을 수 없고 읽어서도 안 된다.
- 조회가 실패하면 웹은 안내 없이 탈퇴 화면을 그린다. 탈퇴를 막지 않는다(FR-019).
- 온보딩 전 회원은 글이 없어 웹이 이 조회를 부르지 않는다.

## R11. 백업을 되살릴 때

**결정**: `infra/RESTORE.md`에 절차를 더한다. 되살린 뒤 백업을 뜬 때부터의 탈퇴는 사라지므로, 그 사이에 탈퇴한 회원이 있었는지 알 길이 없다는 것과, 되살린 뒤 회원이 다시 탈퇴를 요청하면 된다는 것을 적는다.

- 탈퇴한 회원의 번호를 DB 밖에 따로 남기지 않는다. 남기면 그 목록이 새로 지켜야 할 기록이 된다. 회원이 지금 한 자릿수이고 백업은 하루에 한 번이라, 잃는 것은 길어야 하루의 탈퇴다.

## R12. 화면

**결정**: 탈퇴 화면은 `/withdraw` 한 곳이다. 프로필 고치기(`/my/edit`)의 맨 아래와 온보딩 화면의 아래에서 간다. 라우트 가드는 로그인만 보고 온보딩은 보지 않는다.

- FSD: 탈퇴 요청은 `features/withdraw`(뮤테이션과 확인 폼), 화면 조립은 `app/withdraw/page.tsx`, 도움받을 곳은 지금의 `entities/support-resource`를 쓴다. 안내를 보일지 묻는 조회도 `entities/support-resource`에 둔다.
- 확인은 체크 상자 하나다. 닉네임이나 문구를 따라 치게 하지 않는다. 온보딩 전 회원에게는 닉네임이 없고, 따라 치기는 FR-020이 말하는 "여러 번 되묻기"에 가깝다.
- 취소와 탈퇴 버튼을 같은 줄에 같은 크기로 둔다. 탈퇴는 `danger` 색이지만 숨기거나 작게 하지 않는다(FR-020).
- 성공하면 서비스의 첫 화면인 `/login?withdrawn=1`을 새로 받는다(`location.replace`). 세션이 끝났으므로 쿼리 캐시와 레이아웃을 통째로 버리고, 뒤로 가기로 탈퇴 화면에 돌아오지 않는다. 로그인 화면은 `withdrawn`이 있으면 탈퇴가 끝났다는 안내를 한 줄 보인다(US1-AC4).
- 요청을 보내기 전에 "조회의 401은 세션 만료"라는 처리를 멈춘다(`pauseUnauthorizedRedirect`). 탈퇴가 받아들여지면 화면에 떠 있던 조회(내 정보, 안 읽은 알림 수)가 401을 받는데, 그것이 먼저 `/login?next=/withdraw`로 보내면 안내가 보이지 않고, 다시 로그인한 사람이 탈퇴 화면으로 돌아온다. CI의 e2e에서 이 순서로 실패해 알았다. 실패했을 때만 처리를 되돌린다.
