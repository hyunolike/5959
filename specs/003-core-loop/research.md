# Research: 핵심 루프 (003-core-loop)

스펙의 요구사항을 구현 결정으로 옮기면서 검토한 내용이다. 형식은 결정, 근거, 검토한 대안 순서다.

## R1. 모듈 배치와 의존 방향

**결정**: overview 5.1의 그래프를 그대로 쓴다.

| 모듈 | 이 마일스톤의 책임 | 의존 |
|---|---|---|
| `post` | 글, 댓글, 공감, 댓글 공감, 작성자 스냅숏 | `member` |
| `ai` | LLM 게이트웨이(`EmotionAnalyzer`) | 없음 |
| `emotion` | 분석 상태, 재시도, 결과 | `post`, `ai` |
| `monster` | 몬스터 생성, HP 감소, HP 기록 | `post`, `emotion` |
| `feed` | 피드, 글 상세 조회 조합 | `post`, `monster`, `emotion`, `member` |

**근거**: `post`가 `monster`를 알면 순환이 생긴다(`monster`가 `post` 이벤트를 구독하므로). 글에 몬스터를 붙여 보여 주는 일은 `feed`가 맡는다. 공감과 댓글 API는 `post`에 있으므로, 응답에는 공감 수와 댓글 수만 담고 HP는 담지 않는다(R6).

## R2. 비동기 감정 분석과 재시도

**결정**:
1. `post`가 글을 저장하면서 같은 트랜잭션에 `PostCreated`를 발행한다.
2. `emotion`이 `@ApplicationModuleListener`(커밋 후, 비동기)로 받아 `emotion_analysis` 행(`PENDING`, `attempts=0`, `next_attempt_at=now`)을 만들고 곧바로 첫 시도를 한다.
3. 실패하면 `attempts`를 늘리고 `next_attempt_at = now + min(30초 × 2^(attempts-1), 5분)`으로 미룬다.
4. 스케줄러가 10초마다 `PENDING`이고 `next_attempt_at <= now`인 행을 `FOR UPDATE SKIP LOCKED`로 최대 20개 가져와 다시 시도한다.
5. 글 작성 뒤 24시간이 지나도 실패면 `DEFAULTED`로 바꾸고 기본값(무기력, 강도 낮음)으로 `EmotionAnalyzed`를 발행한다.

**근거**:
- 분석 상태와 다음 시도 시각이 테이블에 있어서 재시도 일정을 스펙(FR-004)대로 정확히 지킬 수 있다. Event Publication Registry의 재발행은 간격을 지정할 수 없다.
- 리스너가 실패해도 이벤트는 Registry에 미완료로 남아, `emotion_analysis` 행 생성 자체는 재기동 때 보장된다.
- `SKIP LOCKED`라서 인스턴스가 늘어도 같은 행을 두 번 처리하지 않는다.
- AI 회복 후 대기 시간은 최대 5분(간격 상한)과 10초(스케줄 주기)라서 SC-002(10분)를 지킨다.

**검토한 대안**:
- 원본처럼 동기 호출: constitution V 위반.
- Registry 재발행에만 의존: 재시도 간격과 24시간 기한을 표현할 수 없다.

## R3. LLM 호출

**결정**: Spring AI 2.0.1의 OpenAI 호환 클라이언트를 쓰고, 기준 URL, 모델, 키를 설정으로 받는다. 기본 설정은 원본과 같은 NVIDIA NIM 무료 엔드포인트(`qwen/qwen3-next-80b-a3b-instruct`, temperature 0.1)다.
- 프롬프트는 `src/main/resources/prompts/emotion-analysis-v1.st`로 버전을 붙여 둔다. 원본 v2 프롬프트의 감정 정의와 강도 기준을 옮기되, 욕설 탐지 하위 과제는 뺀다(M4).
- 응답은 `{ "emotion": "ANXIETY", "intensity": "HIGH", "reason": "..." }` JSON으로 받고, 목록에 없는 값이나 파싱 실패는 실패로 본다(스펙 경계 상황).
- Resilience4j(Boot 4용 2.4.0)로 호출마다 20초 타임아웃을 걸고, 서킷 브레이커를 둔다. 서킷이 열리면 호출하지 않고 즉시 실패로 처리해 재시도 일정에 맡긴다.
- `ai` 모듈은 `EmotionAnalyzer` 인터페이스를 공개하고, 테스트와 `e2e` 프로필은 결정적인 가짜 구현을 쓴다. 본문이 `[불안:높음]`처럼 시작하면 그 값을, 아니면 본문 길이로 정한 값을 돌려준다. `[실패]`면 항상, `[실패:N]`이면 처음 N번만 예외를 던진다. 시도 횟수는 글 ID별로 메모리에 센다.

**근거**: 무료 엔드포인트라 월 비용 0원이 유지된다(constitution VI). OpenAI 호환이라 공급자를 설정만으로 바꿀 수 있다. Spring AI 2.x가 Spring Boot 4를 지원한다.

## R4. 몬스터 생성과 소급 반영 (FR-006a)

**결정**: `monster`가 `EmotionAnalyzed`를 같은 방식(커밋 후, 비동기)으로 받아 한 트랜잭션에서 처리한다.
1. 글 단위 잠금 `pg_advisory_xact_lock(post_id)`을 잡는다.
2. 몬스터를 최대 HP로 만든다.
3. `PostApi.attacksSoFar(postId)`로 그 시점에 남아 있는 공격(작성자가 아닌 회원의 공감, 회원별 첫 살아 있는 댓글, 작성자가 아닌 회원의 댓글 공감)을 받는다.
4. 각각을 R5의 규칙으로 HP에 반영한다.

**근거**: 공격 반영(R5)도 같은 글 잠금을 잡으므로, 몬스터 생성과 공격이 동시에 와도 하나는 생성 전 목록에, 다른 하나는 생성 후 감소에 정확히 한 번 들어간다. 잠금이 없으면 생성 시점의 조회와 공격 커밋 사이에 공격이 빠질 수 있다.

## R5. 공격 반영과 동시성 (FR-006, FR-007a, FR-010)

**결정**: `post`가 `PostLiked`, `CommentCreated`, `CommentLiked`를 발행하고, `monster`가 **같은 트랜잭션 안에서**(`@EventListener`, 동기) 반영한다.
1. 행동한 회원이 글 작성자이면 아무것도 하지 않는다.
2. `pg_advisory_xact_lock(post_id)`을 잡고 몬스터를 찾는다. 없으면 아무것도 하지 않는다(나중에 R4가 소급 반영).
3. `monster_hp_log`에 `(monster_id, member_id, action, target_id)` 유일 키로 `INSERT ... ON CONFLICT DO NOTHING`을 실행한다. 삽입되지 않았으면 이미 반영된 공격이므로 멈춘다.
4. `UPDATE monster SET hp = GREATEST(hp - :d, 0), status = CASE ... END WHERE id = :id RETURNING hp, status`로 감소시키고, 기록에 변경 전후 HP를 채운다. 0이 되면 `defeated_at`을 기록하고 `MonsterDefeated`를 발행한다.

**유일 키 규칙**
- 글 공감: `(monster, member, POST_LIKE, post_id)`
- 댓글: `(monster, member, COMMENT, post_id)`. 회원당 글 하나에 한 번이고(명확화 2), 댓글을 지우고 다시 달아도 다시 줄지 않는다.
- 댓글 공감: `(monster, member, COMMENT_LIKE, comment_id)`

**근거**:
- 공감 취소 뒤 다시 공감해도 기록이 남아 있으니 HP는 다시 줄지 않는다. "취소해도 HP는 돌아오지 않는다"와 맞물려 중복 감소를 막는다.
- 같은 트랜잭션이라 공감 저장과 HP 감소가 함께 성공하거나 함께 실패한다. 화면은 응답 직후 상세를 다시 불러와도 바뀐 HP를 본다(FR-017).
- `GREATEST`와 단일 `UPDATE`라서 동시 공격 100건도 빠짐없이 반영된다(SC-004). 글 잠금이 같은 글의 공격을 직렬화하지만, 글마다 잠금이 달라 다른 글끼리는 막지 않는다.

**검토한 대안**:
- 커밋 후 비동기 반영: 화면이 바로 HP를 볼 수 없고, 실패 시 공감은 있는데 HP는 그대로인 상태가 생긴다.
- `post`가 `MonsterApi`를 직접 호출: 순환 의존이 생긴다.

## R6. 공감과 댓글 응답에서 HP 보여 주기 (FR-017)

**결정**: 공감과 댓글 API 응답은 공감 수, 댓글 수, 생성된 댓글만 돌려준다. 웹은 공격 성공 직후 규칙대로 HP를 낙관적으로 줄여 보여 주고(작성자 본인, 이미 반영된 댓글, 몬스터 없음이면 줄이지 않음), 이어서 `feed`의 글 상세를 다시 불러와 실제 값으로 맞춘다.

**근거**: R1의 의존 방향을 지키면서 즉시 반응을 준다. 같은 트랜잭션에서 HP가 바뀌므로 다시 불러온 값은 항상 반영 후의 값이다.

## R7. 피드 조회 (FR-011, SC-003)

**결정**:
- 키셋 페이지네이션이다. 최신순은 `(id DESC)`, 인기순은 `(like_count DESC, id DESC)`이고, 커서는 `base64url("{likeCount}:{id}")` 같은 불투명 문자열이다.
- 직군과 경력 필터는 작성 시점의 작성자 프로필을 글에 스냅숏으로 저장해(`author_job_role`, `author_career_year`) 같은 테이블에서 거른다. 모듈 경계상 `member` 테이블과 조인할 수 없기 때문이다. 프로필 수정 기능이 아직 없어서 현재 값과 같다. 수정 기능이 생기면 `MemberProfileChanged` 이벤트로 스냅숏을 갱신한다.
- `feed`는 `PostApi.page(...)`로 글 20개를 받고, `MonsterApi.findByPostIds`, `EmotionApi.findByPostIds`, `MemberApi.getMembers`를 한 번씩 불러 조합한다. 요청당 쿼리는 4개다.
- 인덱스는 `posts (deleted, id DESC)`, `posts (deleted, like_count DESC, id DESC)`, `posts (author_job_role, author_career_year)`다.
- 커서는 앞 쪽 마지막 글의 값이라, 인기순에서 쪽 사이에 공감 수가 바뀌면 순위가 커서를 넘나든 글이 어긋난다. 커서보다 위로 올라간 글은 이번 스크롤에서 빠지고(새로고침하면 보인다), 커서보다 아래로 내려간 글은 다음 쪽에 다시 온다. 서버는 이를 막지 않는 키셋의 한계로 받아들이고, 웹 피드 목록이 쪽을 펼칠 때 같은 글 ID는 처음 나온 자리에만 두어 두 번 보이지 않게 한다. 최신순은 정렬 키(`id`)가 바뀌지 않아 해당하지 않는다.

**근거**: 오프셋은 새 글이 올라오면 중복과 누락이 생긴다(스펙 경계 상황). 키셋은 새 글 삽입에는 흔들리지 않지만, 인기순의 정렬 키(공감 수)가 바뀌는 경우까지 막으려면 스냅숏 시점을 커서에 담고 이력을 읽어야 해 비용이 크다. 그래서 위의 누락은 받아들이고 중복만 화면에서 없앤다. 조합 쿼리 수가 고정이라 글 1만 개에서도 SC-003(1초)를 지킨다.

## R8. 글자 수 (FR-001, FR-008)

**결정**: 본문 길이는 서버와 웹 모두 사용자가 보는 글자(grapheme cluster) 기준으로 센다. 서버는 `java.text.BreakIterator.getCharacterInstance(Locale.ROOT)`, 웹은 `Intl.Segmenter`를 쓴다. 저장 컬럼은 `text`다. 결합 이모지(👨‍👩‍👧)는 1자이지만 코드 포인트가 5개라, `varchar(n)`(코드 포인트 기준)로는 500자 글이 들어가지 않을 수 있다. 대신 결합 문자를 수없이 겹친 글(Zalgo)과 LLM에 보내는 양을 막으려고 코드 포인트 상한을 따로 둔다: 글 5,000개, 댓글 3,000개. 넘으면 `400 INVALID_REQUEST`다.

**근거**: 스펙 경계 상황에서 "이모지 하나는 1자"라고 했다. UTF-16 길이를 세면 이모지가 2자로 잡혀 사용자 화면의 카운터와 서버 검증이 어긋난다.

## R9. 글 작성 요청 제한 (비용 통제)

**결정**: 회원마다 1시간에 글 10개까지 허용하고, 넘으면 `429 POST_RATE_LIMITED`로 거절한다. 별도 저장소 없이 `posts`에서 최근 1시간 작성 수를 센다(작성자, 시각 인덱스).

**근거**: 글마다 LLM을 한 번 부르므로, 무료 엔드포인트라도 남용을 막아야 한다(overview 5.8). Redis는 M3에서 들이므로 지금은 DB로 충분하다.

## R10. 분석 완료 반영 (FR-015)

**결정**: 상세 화면은 `analysisStatus`가 `PENDING`인 동안 3초마다 상세를 다시 불러오고, 2분이 지나면 15초 간격으로 늦춘다. `ANALYZED`나 `DEFAULTED`가 되면 멈춘다.

**근거**: M3의 SSE 전까지 가장 단순한 방법이다. 대부분 30초 안에 끝나므로(SC-001) 요청 수가 작다.

## R11. 3D 몬스터 (FR-016, FR-017, SC-005, ADR-0003)

**결정**:
- **외형 계산**: `entities/monster/model/appearance.ts`의 순수 함수 `appearance(emotion, hpRatio, status)`가 색, 채도, 크기, 흔들림, 금 간 정도, 표정, 쓰러짐 여부를 돌려준다. HP 단계는 `full`(66% 초과), `hurt`(33% 초과), `weak`(0 초과), `defeated`(0)의 4단계다.
- **감정별 형태**: 불안은 떨리는 뾰족한 몸, 무기력은 축 처진 물방울, 외로움은 작고 웅크린 몸과 큰 눈, 자기비하는 고개 숙인 몸, 짜증은 각지고 붉은 몸이다. 모두 기본 도형과 셰이더로 만든다.
- **3D 장면**: `entities/monster/ui/monster-3d.tsx`(three 0.186, @react-three/fiber 9, @react-three/drei 10)를 `next/dynamic`(`ssr: false`)으로 글 상세에서만 불러온다. 맞으면 0.4초 흔들림과 깜빡임을 보인다.
- **정지 이미지**: `scripts/render-monsters.ts`가 Playwright로 감정 5종 × 단계 4개 = 20장의 PNG를 `public/monsters/{emotion}-{stage}.png`로 뽑아 커밋한다. 피드와 폴백은 이 이미지를 쓴다.
- **폴백**: WebGL 컨텍스트를 만들 수 없거나 `prefers-reduced-motion: reduce`이면 정지 이미지를 쓴다.

**근거**: 외형 로직을 순수 함수로 두면 Vitest로 단계 경계를 테스트할 수 있다. 피드에서 WebGL 캔버스를 여러 개 띄우면 모바일에서 끊기므로(SC-005), 피드는 이미지만 쓴다.

## R12. 테스트 전략

**결정**:
- API: 모듈 테스트, MockMvc 통합 테스트(Testcontainers), 동시성 테스트를 둔다. 동시성 테스트는 같은 몬스터에 서로 다른 회원 100명이 동시 공감하면 HP가 정확히 줄고 기록이 100개인지, 같은 회원이 댓글 둘을 동시에 달면 HP가 3만 주는지를 본다. 가짜 `EmotionAnalyzer`로 실패와 재시도를 시계 주입으로 검증한다.
- 계약: M1과 같은 방식으로 `ContractTests`와 `generated.ts` 드리프트 검사를 하고, 이 마일스톤의 계약 파일을 추가한다.
- 웹: 외형 함수, 낙관적 HP 계산, 글자 수 세기, 폴링 훅을 Vitest로 검증한다.
- 전체 흐름 E2E: 글 쓰기 → 분석 중 → 몬스터, 다른 회원의 공감과 첫 댓글과 두 번째 댓글, 처치, 피드 정렬과 필터, 작성자 자기 공격 무효를 `e2e-full`에서 확인한다. `e2e` 프로필의 가짜 분석기를 쓴다.

## R13. 계약 파일 구조

**결정**: 저장소 루트의 `contracts/openapi.yaml`을 API 전체의 유일한 계약으로 둔다. M1의 `specs/002-auth/contracts/openapi.yaml`을 그대로 옮기고 그 자리에는 루트 파일을 가리키는 안내만 남긴다. M2는 루트 파일에 경로와 스키마를 추가하고, `specs/003-core-loop/contracts/core-loop.openapi.yaml`에 이번 추가분을 단독으로도 유효한 형태로 적어 리뷰하고, 구현 첫 작업에서 루트 파일에 합친다. `gen:api`와 `ContractTests`는 루트 파일만 본다.

**근거**: 마일스톤마다 계약 파일이 따로 있으면 타입 생성과 계약 테스트가 여러 파일을 합쳐야 하고, 공통 스키마(`ErrorEnvelope`, `JobRole` 등)가 중복된다. M1 분석에서 미뤄 둔 항목(I2)을 여기서 해결한다.

**검토한 대안**: `@redocly/cli join`으로 마일스톤 계약을 합치기. 같은 이름의 컴포넌트가 충돌해 접두사가 붙고 생성 타입 이름이 바뀐다.
