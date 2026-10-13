# Data Model: 비회원 둘러보기 피드 (011-guest-feed)

새 테이블과 마이그레이션은 없다.

## safety 모듈 (더하는 것)

`RiskApi.clearedPostIds(postIds): Set<Long>`: [postIds] 가운데 가장 새 판정이 `DONE`이고 단계가 없음인 글. AI를 기다리는 글, 키워드로만 닫힌 글(`FALLBACK`), 판정 기록이 없는 글은 들지 않는다. 쿼리 한 번이다(research R8).

## feed 모듈 (더하는 것)

`PublicFeedQuery.get(): PublicFeedResponse`

| 값 | 규칙 |
|---|---|
| 읽는 글 | `PostApi.page`로 최신순 50개(보는 회원 없음) |
| 거르기 | `riskLevel == NONE`이고 `clearedPostIds`에 있다 |
| 내보내는 수 | 앞에서부터 20개 |
| `contentPreview` | 욕설을 가린 뒤 회원의 피드와 같은 길이로 자른다 |
| `jobRole`, `careerYear` | 글에 적힌 스냅숏 |

허용 의존에 `safety`를 더한다.

## member 모듈 (고치는 것)

`SecurityPaths.PUBLIC_GET`에 `/api/v1/public/feed`를 더한다(`GET`만 연다).

## 설정

`ogu.feed.public.size`는 두지 않는다. 20과 50은 코드의 상수다. 바꿀 일이 생기면 그때 설정으로 뺀다.
