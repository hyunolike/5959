# Research: 위험 감지와 안전장치 (005-safety)

plan의 Technical Context에서 정해야 했던 것과 그 근거다. 번호는 plan, data-model, tasks에서 `R1`처럼 가리킨다.

## R1. 모듈 배치와 의존 방향

**결정**: 새 모듈 `safety`를 만든다. `safety`는 `post`, `ai`, `member`, `shared`에만 의존한다. `notification`은 `safety`의 이벤트를 받는다(`notification → safety`). overview 5.1의 그래프와 같다.

- `safety`가 소유하는 것: 위험 판정, 판정 재시도 일정, 신고, 재검토 요청, 운영자 처리 기록, 도움 리소스, 낱말 목록(위기, 우려, 욕설, 허용).
- `post`가 소유하는 것: 글과 댓글의 숨김 상태(`hidden_at`). 숨김은 "보이는가"를 정하는 글의 속성이고 `post`의 모든 조회가 그 조건을 걸어야 하므로 `post`에 둔다. `safety`는 `PostModerationApi`(새 파사드)로 숨기고 푼다.
- `post`는 `safety`를 모른다. `post`가 `safety`를 부르면 `safety → post`와 순환이 된다.

**검토한 대안**:
- 숨김 상태를 `safety`의 테이블에 두고 조회 때마다 `safety`에 묻기: 피드 한 쪽마다 파사드 호출이 하나 늘고, 키셋 쿼리의 `WHERE`에 걸 수 없어 쪽 크기가 들쭉날쭉해진다.
- `post`에 판정까지 넣기: `post`가 `ai`와 낱말 목록, 신고를 알게 돼 모듈이 커진다.

## R2. 저장과 같은 트랜잭션에서 키워드 규칙을 돌린다 (SC-001)

**결정**: `post`가 글과 댓글을 저장하거나 고칠 때 같은 트랜잭션 안에서 새 이벤트 `PostWritten`, `CommentWritten`을 낸다. `safety`의 `ScreeningListener`가 `@EventListener`(동기, 같은 트랜잭션)로 받아 키워드 규칙을 돌리고, 위기면 그 자리에서 `PostModerationApi.hide`를 부른다. 글 저장과 숨김이 함께 커밋되므로 목록에 있는 위기 표현이 든 글은 한 번도 공개되지 않는다.

- M2의 `AttackListener`(공감과 HP 감소가 같은 트랜잭션)와 같은 방식이다.
- 이벤트에는 본문을 싣지 않는다. 리스너가 `PostModerationApi.contentOf`로 다시 읽는다(같은 트랜잭션이라 방금 쓴 행이 보인다).
- 키워드 규칙이 예외를 던지면 글 저장도 실패한다. 그래서 규칙은 메모리 안의 순수 계산만 하고(R4), 낱말 목록을 읽지 못한 경우에도 마지막으로 읽은 목록으로 판정한다. 기동 직후 한 번도 읽지 못했으면 코드에 넣어 둔 최소 목록(원본의 6개)을 쓴다.
- 기존 `PostCreated`(커밋 뒤 비동기, 감정 분석용)와 `CommentCreated`(같은 트랜잭션, HP용)는 그대로 둔다. 수정에는 이벤트가 없었으므로 새 이벤트가 필요하다.

**검토한 대안**:
- `PostCreated`를 커밋 뒤에 받아 숨기기: 커밋과 숨김 사이에 글이 피드에 보인다. SC-001을 지킬 수 없다.
- 키워드 규칙을 `shared`에 두고 `post`가 직접 부르기: 가능하지만 판정 기록과 알림 발행이 `safety`에 있어 결국 둘로 나뉜다.

## R3. AI 분류와 재시도

**결정**: `ai` 모듈에 `RiskClassifier`를 더한다(`classify(text): RiskClassification`, 실패하면 `RiskClassificationFailed`). `safety`는 M2의 `emotion_analysis`와 같은 방식의 일정 테이블 `risk_assessment`로 재시도한다.

1. `ScreeningListener`가 같은 트랜잭션에서 키워드 판정을 `risk_assessment`에 `PENDING`으로 적는다(키워드 단계 포함).
2. 커밋 뒤 `@ApplicationModuleListener`가 바로 한 번 AI 분류를 시도한다. LLM 호출은 트랜잭션 밖에서 한다.
3. 성공하면 `max(키워드 단계, AI 단계)`를 최종 단계로 적고 `DONE`으로 닫는다. 최종 단계가 키워드 단계보다 높으면 그때 숨김과 알림이 일어난다.
4. 실패하면 `min(30초 × 2^(n-1), 5분)` 뒤로 미루고 `RiskRetryScheduler`가 `FOR UPDATE SKIP LOCKED`로 다시 맡는다. 쓴 지 24시간이 지나면 키워드 단계를 최종으로 `FALLBACK`으로 닫는다.

- 감정 분석과 한 번의 호출로 합치지 않는다. 댓글은 감정 분석을 하지 않고, 두 모듈의 재시도 일정과 실패 처리가 서로 묶인다. 호출이 글마다 한 번, 댓글마다 한 번 늘어난다(AI 사용료, constitution VI의 예외 범위).
- Resilience4j 서킷 브레이커는 감정 분석과 따로 둔다(`riskClassifier`). 한쪽 프롬프트의 실패가 다른 쪽을 열지 않게 한다.
- 모델 응답은 `{"level":"NONE|CONCERN|CRISIS"}`만 받는다. 근거 문장은 받지 않는다(저장할 민감 정보를 늘리지 않는다, FR-015).
- 키가 없으면 `DisabledRiskClassifier`가 바로 실패해 키워드 규칙만으로 동작한다. `e2e` 프로필은 본문 머리말(`[위기]`, `[우려]`, `[위험분류실패:2]`)로 결과를 정하는 `FakeRiskClassifier`를 쓴다.

**검토한 대안**:
- 저장 요청 안에서 AI를 동기로 부르기: 쓰기 p95 300ms(SC-004)와 constitution V를 어긴다.

## R4. 키워드 규칙과 정규화

**결정**: 본문을 정규화한 뒤 낱말 목록의 표현이 들어 있는지 본다.

- 정규화: NFKC, 소문자, 공백과 문장 부호와 기호 제거, 같은 글자가 세 번 이상 이어지면 두 번으로 줄임. `죽 고 싶 어`, `죽.고.싶.어`, `죽고싶어어어어`가 같은 표현으로 잡힌다(US2-AC4).
- 단계: 위기 목록에 걸리면 `CRISIS`, 아니고 우려 목록에 걸리면 `CONCERN`, 아니면 `NONE`.
- 부정문이나 인용은 가리지 않는다. "죽고 싶지는 않다"도 위기다. 놓치는 것보다 잘못 숨기는 쪽을 택했고(스펙 Edge Cases), 잘못 숨긴 것은 재검토 요청과 운영자가 푼다.
- 욕설 가리기는 원문의 위치를 알아야 하므로 정규화한 글자와 원문 글자의 위치 대응표를 함께 만든다. 가릴 때는 원문에서 그 구간(사이에 낀 공백과 기호 포함)을 `*`로 바꾼다. 허용 목록의 낱말 안에 든 욕설은 가리지 않는다(US5-AC3).
- 글자 단위는 M2와 같이 grapheme이다(`shared/text/Grapheme`).

**낱말 목록의 보관**: `safety_term` 테이블에 둔다(종류 `CRISIS`, `CONCERN`, `PROFANITY`, `ALLOW`). 운영자 API로 더하고 뺀다(FR-002, FR-014). 인스턴스마다 메모리에 올려 두고 30초마다 `max(updated_at)`과 행 수를 확인해 바뀌었으면 다시 읽는다. 목록을 고친 뒤 모든 인스턴스에 퍼지기까지 최대 30초다.

**검토한 대안**:
- 형태소 분석기: 의존성과 사전 관리가 커지고, 위기 표현은 짧은 관용구라 부분 문자열로 충분하다.
- 설정 파일(yaml): 고치려면 배포가 필요하다(FR-002 위반).
- Redis pub/sub로 목록 변경 알리기: 가능하지만 30초 지연이 문제 되지 않는다.

## R5. 숨김 상태의 표현과 조회 조건

**결정**: `posts`와 `comments`에 `hidden_at TIMESTAMPTZ`, `hidden_reason VARCHAR(20)`(`RISK`, `OPERATOR`)을 더한다.

- "다른 회원에게 보이는가"는 `deleted_at IS NULL AND hidden_at IS NULL`이다. 지금 `deleted_at IS NULL`을 거는 조회 30여 곳을 둘로 나눈다.
  - **모두에게 보이는 것만**: 피드, 공감한 글, 다른 회원의 글 상세, 공감과 댓글 쓰기의 대상 확인, `attacksSoFar`, 알림의 대상 확인.
  - **작성자에게는 숨긴 것도**: 글 상세(내 글), 내가 쓴 글, 내 댓글, 감정 통계의 `liveRefsByAuthor`, 글과 댓글 수정과 삭제.
- 조건이 흩어지지 않게 `post/application/Visibility.kt`에 SQL 조각 두 개(`VISIBLE`, `OWNED_OR_VISIBLE(viewer)`)를 두고 모든 쿼리가 그것을 쓴다. JPA 파생 쿼리(`findByIdAndDeletedAtIsNull`)는 `findVisibleById`, `findOwnedOrVisibleById`로 바꾼다.
- 피드의 부분 인덱스 세 개(`posts_feed_latest_idx`, `posts_feed_popular_idx`, `posts_feed_author_profile_idx`)는 조건을 `deleted_at IS NULL AND hidden_at IS NULL`로 바꿔 다시 만든다. 내 글 인덱스(`posts_author_live_idx`)는 그대로 둔다.
- 숨긴 글의 공감 수, 댓글 수, 몬스터 HP는 그대로 둔다. 숨김을 풀면 그대로 다시 보인다.
- 숨긴 글에 대한 공감, 댓글, 댓글 공감은 지운 글과 같이 `404 POST_NOT_FOUND`다(US1-AC8). 작성자 자신도 숨긴 글에는 댓글을 달 수 없다(다른 사람이 볼 수 없는 글에 대화가 이어지지 않게 한다).
- **숨긴 댓글**: 댓글 목록에서 자리는 남기고 `hidden: true`, 본문과 작성자는 null로 준다(다른 회원). 작성자 자신에게는 본문과 `hidden: true`를 함께 준다. 답글은 그대로 준다(스펙 Clarifications). 숨긴 댓글에 새 답글과 공감은 받지 않는다(`404 COMMENT_NOT_FOUND`).
- 댓글 수(`comment_count`)는 숨겨도 줄이지 않는다. 자리가 보이기 때문이다.
- 작성자가 스스로 지운 글은 `deleted_at`이 우선한다. 운영자가 숨김을 풀어도 지운 글은 보이지 않는다.

**검토한 대안**:
- 숨김을 `deleted_at`에 합치기: 작성자에게 보여 줄 수 없고 되돌릴 수 없다.
- PostgreSQL 뷰(`visible_posts`): JPA 엔티티와 JdbcClient 쿼리가 섞여 있어 뷰를 끼우면 쓰기 경로와 읽기 경로의 이름이 갈린다.

## R6. 욕설 가리기는 읽을 때 한다 (FR-014)

**결정**: 원문은 그대로 저장하고 응답을 만들 때 가린다. 가리는 일은 `shared/text/ContentMask` 인터페이스(`mask(text: String): String`)로 하고, 구현(`ProfanityMask`)은 `safety`가 빈으로 내놓는다.

- 본문을 내보내는 곳은 `post`(댓글 목록), `feed`(피드, 글 상세, 내 활동), `notification`(글 앞부분) 세 모듈이다. `post`는 `safety`를 알 수 없으므로(R1) 세 모듈이 모두 `shared`의 인터페이스만 보고, `safety`가 구현을 준다. 타입 의존은 모두 `shared`를 향해 순환이 없다.
- `safety`를 뺀 모듈 테스트(`@ApplicationModuleTest`)에서는 구현 빈이 없으므로 `shared`에 아무것도 가리지 않는 기본 구현을 `@ConditionalOnMissingBean`으로 둔다.
- 보는 사람이 작성자면 가리지 않는다(US5-AC5). 호출하는 쪽이 `viewerId == authorId`를 보고 건너뛴다.
- 미리보기(앞 50글자)는 가린 뒤에 자른다. 잘린 욕설이 가려지지 않는 일을 막는다.
- 감정 분석과 위험 감지는 `PostApi`와 `PostModerationApi`가 주는 원문으로 한다(US5-AC4).
- 목록을 고치면 다음 조회부터 예전 글에도 적용된다(US5-AC6). 저장된 본문은 바뀌지 않는다.
- 비용: 글 하나에 정규화와 부분 문자열 찾기 한 번이다. 낱말 수백 개, 본문 500자에서 수십 마이크로초 수준이라 피드 한 쪽(20개)에 영향이 없다. 목표는 tasks의 측정으로 확인한다.

**검토한 대안**:
- 저장할 때 원문을 바꾸기(원본 방식): 목록을 고쳐도 예전 글은 그대로이고, 감정 분석과 위험 감지가 가려진 글을 보게 된다. 원문을 되돌릴 수 없다.
- 가린 본문을 열로 따로 저장하기: 목록을 고칠 때마다 전체를 다시 계산해야 한다.
- 웹에서 가리기: 원문이 다른 회원의 브라우저까지 간다.

## R7. 작성자에게 도움을 안내하는 두 경로 (SC-003)

**결정**: 응답과 알림 두 경로로 안내한다.

1. **응답**: 글 상세(`PostDetail`)와 댓글 항목에 작성자에게만 `safety` 필드를 준다(`{ level, hidden, reviewRequested }`). 웹은 글을 올린 뒤 상세로 이동하므로, 키워드 규칙으로 판정된 글은 상세의 첫 응답에 이미 들어 있다. 댓글은 작성 응답에 같은 필드를 넣는다. `feed`와 `post`의 표현 계층이 `SafetyApi.statusOf`를 부르는 것이 아니라, 숨김 여부는 `post`의 열에서, 단계는 `PostModerationApi`가 함께 저장한 `risk_level` 열에서 읽는다(아래).
2. **알림**: `safety`가 `RiskDetected(targetType, targetId, postId, authorId, level)`를 내고 `notification`이 `SUPPORT_NOTICE` 알림을 만든다. AI가 뒤늦게 알아본 경우와 다른 화면을 보고 있는 경우를 맡는다. M3 측정에서 알림은 p95 39ms에 도착했다.

- `post`의 응답이 단계를 알 수 있도록 `posts.risk_level`, `comments.risk_level`(`NONE`, `CONCERN`, `CRISIS`) 열을 둔다. `safety`가 판정할 때 `PostModerationApi.markRisk`로 적는다. 값은 작성자에게만 응답에 실리고 다른 회원에게는 나가지 않는다.
- 알림 문구는 단계 이름을 쓰지 않는다. 우려와 위기 모두 "마음이 많이 힘드신가요? 도움받을 수 있는 곳을 안내해 드려요"다(FR-007). 누르면 그 글(작성자에게는 보인다)로 간다.
- 한 대상에 대해 단계가 올라갈 때만 알림을 만든다. 멱등 키는 `RISK:{type}:{id}:{level}`이다.
- 도움 리소스 목록은 `GET /api/v1/safety/support-resources`로 받는다. 응답마다 싣지 않는다(바뀌는 일이 드물어 웹이 캐시한다).

## R8. 알림과 숨긴 글 (ADR-0005의 약속)

**결정**:
- `PostApi.find`와 `PostApi.findComment`는 숨긴 글과 댓글을 지운 것처럼 null로 돌려준다. 알림 생성 규칙이 이 둘로 대상을 확인하므로, 숨긴 글에는 댓글, 공감, 몬스터 알림이 새로 생기지 않는다(US1-AC7).
- `PostApi.previews`는 숨긴 글을 `deleted = true`로 준다. 이미 있는 알림은 "삭제된 글"로 보인다. 받는 사람이 그 글의 작성자면 미리보기를 그대로 준다(자기 글은 자기에게 보인다). 그래서 `previews(postIds, viewerId)`로 시그니처를 바꾼다.
- 새 알림 종류 셋은 받는 사람이 언제나 작성자이고 대상이 숨겨져 있을 수 있으므로, 대상 확인에 `find` 대신 작성자 기준 조회를 쓴다.

| 종류 | 받는 이벤트 | 문구 | 멱등 키 |
|---|---|---|---|
| `SUPPORT_NOTICE` | `RiskDetected` | 마음이 많이 힘드신가요? 도움받을 수 있는 곳을 안내해 드려요 | `RISK:{type}:{id}:{level}` |
| `CONTENT_RESTORED` | `ContentRestored` | 가려졌던 글이 다시 보여요 | `RESTORED:{type}:{id}:{actionId}` |
| `REVIEW_KEPT` | `ReviewResolved`(유지) | 요청하신 글을 다시 살펴봤어요 | `REVIEW:{requestId}` |

- 몬스터 처치 알림: 숨긴 글의 몬스터가 숨기기 전의 공격으로 처치되는 일은 없다(숨긴 뒤에는 공격이 거절된다). 소급 반영으로 생성과 함께 처치되는 경우는 `find`가 null이라 알림이 생기지 않는다.

## R9. 신고

**결정**: `report` 테이블(`reporter_id`, `target_type`, `target_id`, `reason`, `detail`, `status`). `(reporter_id, target_type, target_id)` 유일 제약으로 중복을 막는다(`409 ALREADY_REPORTED`).

- 대상은 보이는 글과 댓글만이다. 숨겼거나 지운 대상은 `404`다. 대상의 작성자는 `PostApi.find`, `findComment`로 확인하고 자기 것이면 `403 CANNOT_REPORT_OWN_CONTENT`다.
- 한 시간 20건 제한은 M2의 글 작성 제한과 같은 방식이다. 회원 단위 advisory lock(새 이름공간)을 잡고 최근 한 시간의 신고 수를 센다. 넘으면 `429 REPORT_RATE_LIMITED`와 `Retry-After`다.
- 사유는 `DANGEROUS`, `ABUSIVE`, `SPAM`, `OTHER`다. `detail`은 200자(grapheme)까지이고 `OTHER`일 때만 받는다.
- 응답과 다른 회원의 어떤 조회에도 신고 수나 신고 여부를 싣지 않는다(FR-010). 내가 이미 신고했는지는 다시 신고했을 때의 409로만 안다.
- 대상이 지워지면 열린 신고를 닫는다. `post`가 삭제 때 내는 이벤트가 없으므로 `PostRemoved`, `CommentRemoved`를 더하고 `safety`가 받는다.
- 신고가 쌓여도 숨기지 않는다(FR-017).

## R10. 운영자

**결정**: `member.role`(`MEMBER`, `OPERATOR`, 기본 `MEMBER`) 열을 둔다. 지정은 SQL로 한다(quickstart에 절차). 화면은 없다.

- 운영자 경로는 `/api/v1/operator/**`다. `safety`의 컨트롤러가 요청마다 `MemberApi.isOperator(memberId)`로 확인한다. JWT에 역할을 넣지 않는다. 넣으면 권한을 거둔 뒤에도 access 토큰이 살아 있는 15분 동안 통한다.
- 운영자가 아니면 `404 NOT_FOUND`다(US4-AC6). 403을 주면 경로가 있다는 것이 드러난다.
- 웹의 BFF 프록시는 `operator/**`를 넘기지 않는다(404). 운영자는 API를 직접 부른다(curl, quickstart에 예시). 브라우저 세션이 운영자 기능에 닿는 길을 만들지 않는다.
- 처리마다 `moderation_action`에 누가, 언제, 무엇을 했는지 남긴다(FR-012).

**운영자 기능**:
- 조회: 위험 판정(단계, 상태로 거름), 신고(대상별 신고 수 포함), 재검토 요청. 모두 키셋.
- 처리: 대상 숨기기, 숨김 풀기, 신고 닫기(처리됨, 기각), 재검토 닫기(유지, 해제), 낱말 더하기와 빼기.
- 도움 리소스는 API를 두지 않고 `support_resource` 테이블을 SQL로 고친다(quickstart에 절차). 바뀌는 일이 드물고, 배포 없이 고칠 수 있으면 된다(스펙 Edge Cases).
- 조회 응답에는 대상의 본문을 싣는다(판단하려면 봐야 한다). 운영자 조회는 로그에 본문을 남기지 않는다.

## R11. 재검토 요청

**결정**: `review_request` 테이블. `(target_type, target_id)` 유일 제약으로 대상마다 하나다(`409 REVIEW_ALREADY_REQUESTED`).

- 숨겨진 대상의 작성자만 요청할 수 있다. 숨겨지지 않았거나 남의 것이면 `404`다.
- 접수하면 `PostModerationApi.markReviewRequested`로 대상 행의 `review_requested_at`을 적는다. 글 상세와 댓글 응답의 `safety.reviewRequested`는 이 열에서 읽는다. `post`와 `feed`가 `safety`에 묻지 않아도 된다(단계를 `risk_level` 열에 두는 것과 같은 이유, R7).
- 운영자가 숨김을 풀면 `ContentRestored`가, 유지하면 `ReviewResolved(kept = true)`가 나가 작성자에게 알림이 간다(R8).
- 유지로 닫힌 뒤에는 다시 요청할 수 없다. 글을 고쳐 다시 판정받아도 숨김은 자동으로 풀리지 않는다(US2-AC5).

## R12. 글을 고쳤을 때

**결정**: 고칠 때마다 `PostWritten(edited = true)`가 나가 다시 판정한다.

- 단계가 올라가면 숨기고 알린다. 내려가도 숨김은 그대로 둔다(US2-AC5). 위기 표현을 지우는 것만으로 숨김을 풀 수 있으면 숨김이 의미가 없다.
- `risk_assessment`는 대상마다 가장 최근 것이 지금 상태다. 이전 행은 기록으로 남는다.
- 고치는 사이에 이전 내용의 AI 분류가 돌고 있을 수 있다. 분류 결과를 적을 때 `risk_assessment`의 `content_version`(대상의 `updated_at`)이 지금과 다르면 버린다.

## R13. 민감 정보 (FR-015, SC-008)

**결정**:
- `risk_assessment`에는 걸린 표현이나 본문 사본을 저장하지 않는다. 단계와 방법(`KEYWORD`, `AI`, `BOTH`)만 둔다. 운영자는 조회 때 원문을 `PostModerationApi`로 읽는다.
- 로그에는 대상 ID와 단계만 남긴다. LLM 요청과 응답 원문은 남기지 않는다(M2와 같다).
- Sentry: 서버는 M1에서 지역 변수와 요청 본문 전송을 껐다. 웹은 신고 설명과 글 본문이 든 폼 값을 breadcrumb에 싣지 않도록 `scrubEvent`에 경로(`/api/reports`, `/api/posts`)를 더한다.
- 보관: 판정, 신고, 재검토 요청, 처리 기록은 1년 뒤 `SafetyPurgeJob`이 지운다(FR-018). 숨김 상태는 `post`의 열이라 남는다.

## R14. 이미 있는 글 훑기

**결정**: 출시 때 한 번 `SafetyBackfill`이 M2, M3의 글과 댓글을 키워드 규칙으로 훑는다.

- `safety_backfill` 표지 행(끝난 ID)으로 이어서 하고, 끝나면 다시 돌지 않는다. 1,000행씩 읽는다.
- 위기면 숨기고 작성자에게 `SUPPORT_NOTICE`를 보낸다. AI 분류는 예약하지 않는다(비용과 양). 우려는 기록만 한다.
- 두 인스턴스가 함께 떠도 advisory lock으로 한쪽만 돈다.

## R15. 평가용 문장 묶음 (SC-005)

**결정**: `apps/api/src/test/resources/safety/eval-set.tsv`에 위기 50, 우려 50, 위험 없음 100문장을 둔다(직접 쓴 문장, 실제 회원 글을 쓰지 않는다).

- 키워드 규칙의 성적은 단위 테스트로 잰다. 키워드 규칙만으로는 위기 재현율이 목표(95%)에 못 미칠 수 있다. 목록에 없는 표현은 AI가 맡기 때문이다. 테스트는 수치를 출력하고, 단언은 "목록에 있는 표현은 100% 잡는다"와 "위험 없음 100문장을 위기로 보는 비율이 10% 이하"만 건다.
- AI까지 합친 성적(SC-005의 목표)은 실제 키가 있어야 하므로 quickstart의 수동 절차로 재고 결과를 적는다. 키가 없으면 재지 못했다고 적는다(M2 SC-001과 같은 처리).

## R16. 웹

**결정**:
- **슬라이스**: `entities/safety`(도움 리소스 쿼리, `SupportResources` UI, 타입), `features/report-content`(신고 대화상자와 뮤테이션), `features/request-review`(재검토 요청), `widgets/safety-banner`(글 상세의 숨김 설명과 도움 안내). 댓글의 "가려진 댓글이에요" 자리는 `entities/comment`의 항목이 그린다.
- **도움 안내**: 위기는 글 상세 위쪽에 닫을 수 없는 안내로, 우려는 접을 수 있는 안내로 보인다. 전화번호는 `tel:` 링크다. `SUPPORT_NOTICE` 토스트는 다른 알림보다 오래(8초) 머물고 누르면 그 글로 간다.
- **신고**: 글 상세와 댓글의 메뉴에 "신고"를 둔다. 내 글과 댓글에는 보이지 않는다. 성공과 409 모두 같은 "신고가 접수됐어요" 흐름으로 닫되 409는 "이미 신고한 글이에요"라고 알린다.
- **숨긴 글**: 내 글 목록의 카드에 "다른 회원에게 보이지 않아요" 표시. 상세에는 설명, 도움 리소스, 재검토 요청 버튼.
- **운영자 화면은 없다**(R10).
- **가리기**: 웹은 받은 문자열을 그대로 그린다. 가리는 계산을 하지 않는다.

## R17. 테스트 전략

- **API**: 키워드 정규화와 가리기는 순수 함수라 단위 테스트(표 형식). 판정과 숨김은 `@SpringBootTest` 통합 테스트로 "저장 응답 직후 다른 회원의 피드에 없다"를 본다. AI 실패는 `FakeRiskClassifier`의 머리말로 만든다. 재시도 일정은 시계 주입. 조회 조건을 둘로 나눈 것(R5)은 기존 테스트 757개가 회귀를 잡고, 숨긴 글에 대한 표 형식 테스트(조회 경로 × 보는 사람)를 더한다.
- **모듈 경계**: `ModularityTests`가 `safety → post` 한 방향과 `ContentMask`의 배선을 확인한다.
- **웹**: 배너, 신고 대화상자, 가려진 댓글 자리의 단위 테스트. e2e-full에 `safety.spec.ts`(작성자와 다른 회원 두 컨텍스트).
- **성능**: 글 작성과 댓글 작성 p95(SC-004), 피드 한 쪽의 가리기 비용을 M2와 같은 방법으로 잰다.
