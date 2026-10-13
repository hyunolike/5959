# Quickstart: 비회원 둘러보기 피드 검증 (011-guest-feed)

## 자동 검증

```bash
cd apps/api && ./gradlew test --tests "*PublicFeed*" --tests "*RiskApiTests*" --tests "*SecurityIntegrationTests*"
pnpm --filter web test
pnpm --filter web test:e2e
pnpm --filter web test:e2e:full          # guest-security.spec.ts
```

## 수동 검증 (로컬, e2e 프로필)

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | 회원 A가 `[불안:낮음]` 글을 쓴다. 로그아웃한 창에서 로그인 화면의 "먼저 둘러보기"를 누른다 | 그 글이 목록에 보인다. 감정, 직군과 경력, 본문, 몬스터와 HP, 공감과 댓글 수 | US1-AC1, US1-AC2 |
| 2 | 목록의 글쓴이 줄과 개발자 도구의 응답을 본다 | 닉네임과 회원 번호가 없다 | US1-AC3 |
| 3 | 글을 누른다. 취소한다. 다시 눌러 로그인한다 | 로그인 안내가 열린다. 취소하면 홈에 남고, 로그인하면 그 글의 상세로 간다 | US1-AC4 |
| 4 | A가 글 하나를 지우고, 운영자가 하나를 숨긴다 | 둘 다 목록에서 빠진다 | US2-AC1 |
| 5 | A가 `[우려]` 글과 `[위험분류실패]` 글을 쓴다 | 둘 다 목록에 없다 | US2-AC2, US2-AC3 |
| 6 | 로그인하지 않고 `/post/1`, `/api/posts/1/comments`, `/api/feed`를 연다 | 로그인 화면으로 가거나 401이다 | US2-AC5 |
| 7 | 홈의 HTML에서 `robots`를 찾는다 | `noindex`가 있다 | US2-AC6 |
| 8 | 로그인해 글쓰기 화면을 본다 | 올린 글이 로그인하지 않은 방문자에게도 닉네임 없이 보인다는 안내 | US3-AC1 |

## 배포

병합하면 웹은 바로 배포된다. **API는 따로 올려야 한다**(`infra/gcp/README.md` 4절). DB 변경은 없다. API가 올라가기 전에는 비회원 홈에 "이야기를 불러오지 못했어요"가 보이고 나머지 안내는 그대로다.
