# Data Model: 첫 글로 이어지는 안내 (016-first-post-guide)

서버의 데이터는 바꾸지 않는다. 마이그레이션이 없다. 아래는 화면이 읽거나 브라우저에 두는 값이다.

## 서버에서 읽는 값

| 값 | 어디서 | 쓰는 곳 |
|---|---|---|
| 내 글이 있는가 | `GET /api/members/me/posts?size=1`의 `items.length > 0` | 첫 고민 카드(FR-012), 첫 글 표시(FR-014) |
| 내 글인가 | 글 상세의 `mine` | 첫 글 안내, "나도 털어놓기" |
| 위험 판정 | 글 상세의 `safety`(`needsSafetyNotice`) | 첫 글 안내를 숨김(FR-017) |
| AI 친구가 쓴 글인가 | 글 상세의 `author.ai` | "나도 털어놓기"는 AI 글에도 보임(US4-AC3) |

`hasMyPost`의 상태: `undefined`(모름, 실패 포함) → `true` / `false`. `undefined`일 때는 아무 안내도 내지 않는다.

## 브라우저에 두는 값

| 키 | 저장소 | 값 | 생김 | 사라짐 |
|---|---|---|---|---|
| `ogu.welcome.pending` | sessionStorage | `"1"` | 온보딩 성공, 가려던 곳이 `/home` | 홈이 처음 그릴 때 읽고 바로 지움 |
| `ogu.firstPostPrompt.dismissed` | localStorage | `"1"` | 첫 고민 카드의 닫기 | 지우지 않음. 글을 쓰면 카드 조건이 거짓이 되어 의미가 없어짐 |

모든 읽기와 쓰기는 `try/catch`로 감싼다. 실패하면 "없음"으로 본다.

## 주소의 값

| 값 | 생김 | 사라짐 |
|---|---|---|
| `/post/{id}?first=1` | 쓰기 성공 직전 `hasMyPost === false` | 글 화면이 처음 그릴 때 상태로 옮기고 `router.replace`로 지움 |

## 고정 문구

| 이름 | 자리 | 내용 |
|---|---|---|
| `WRITE_STARTERS` | `features/write-post/model` | 실마리 3~5개(research R5) |
| `COMMENT_TONE_EXAMPLES` | `entities/post/model/types.ts` | 말투 네 가지마다 한 줄 |

## 화면 상태

```text
홈(회원)
  welcome.pending 있음            → 환영 안내(welcome)
  아니고 hasMyPost === false
        그리고 닫은 적 없음        → 첫 고민 카드(nudge)
  그 밖(true, undefined, 닫음)    → 없음

글 화면
  first && mine && !safetyNotice  → 첫 글 안내 (닫으면 사라짐)
  !mine                           → "나도 털어놓기"
```
