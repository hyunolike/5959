# UI Contract: 첫 글로 이어지는 안내 (016-first-post-guide)

HTTP API는 바꾸지 않는다(`contracts/openapi.yaml` 그대로). 이 문서는 화면 사이의 약속이다.

## 라우트와 이동

| 출발 | 조건 | 도착 |
|---|---|---|
| 온보딩 성공 | `next`가 없거나 `/home` | `/home` + `sessionStorage['ogu.welcome.pending']='1'` |
| 온보딩 성공 | `next`가 다른 경로 | `next` (지금과 같음) |
| 쓰기 성공 | `hasMyPost === false` | `/post/{id}?first=1` |
| 쓰기 성공 | 그 밖 | `/post/{id}` (지금과 같음) |
| 글 화면 `?first=1` | 처음 그릴 때 | 같은 화면, 쿼리 없이 `replace` |

`proxy.ts`의 가드 규칙은 바꾸지 않는다.

## 이미 있는 API의 새 쓰임

- `GET /api/members/me/posts?size=1` — 내 글이 있는지만 본다. `size`는 계약의 `Size` 매개변수다.

## 내보내는 것(FSD 공개 API)

| 슬라이스 | 새로 내보냄 |
|---|---|
| `entities/post` | `useHasMyPostQuery`, `COMMENT_TONE_EXAMPLES` |
| `entities/monster` | `MonsterLifecycleLoop` |
| `features/write-post` | `PrivacyScope` |
| `features/install-app` | `useInstallApp` |
| `widgets/first-post-prompt` | `FirstPostPrompt` (새 위젯) |
| `shared/config` | `QUERY_KEYS.hasMyPost` |

## 방문 분석 이벤트

`data-umami-event` 값(`write-submit`은 누른 수라 참고용): `welcome-write`, `welcome-browse`, `first-post-card-write`, `first-post-card-dismiss`, `write-starter`, `write-submit`, `detail-write-own`. 이름은 바꾸면 기준선이 끊기므로 고정한다.

## 바뀌는 기존 문구(테스트가 기대하는 것)

| 화면 | 지금 | 바뀜 |
|---|---|---|
| 쓰기 머리말 | "올린 글의 앞 50자는 로그인하지 않은 방문자에게도 닉네임 없이 보여요." | 지움. `PrivacyScope` 안의 줄로 옮김 (011 US3-AC1 테스트 갱신) |
| 쓰기 입력란 설명 | "형식 없이 편하게 적어 주세요. 닉네임, 직군, 경력과 함께 회원에게만 보여요." | "형식 없이 편하게 적어 주세요." |
| 둘러보기 3단계 마지막 | "공감과 댓글이 모일수록 몬스터가 약해져요." | 물리친 몬스터가 도감 카드로 남는다는 문장을 더함 |

AI 친구 문장(012 US4-AC7)은 그대로 둔다.
