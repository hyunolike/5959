# 오구오구 디자인 시스템

직장인의 고민을 나누고 공감으로 감정 몬스터를 함께 물리치는 커뮤니티다. 피드는 글을 읽기 좋게, 글 상세와 레이드는 몬스터가 주인공이 되게 한다. 브랜드 색은 두지 않는다. 화면의 색은 다섯 감정에서만 나온다.

설계와 결정의 까닭은 저장소 루트의 `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md`에 있다. 토큰과 컴포넌트는 `ENABLE_DESIGN_CATALOG=1`로 띄운 `/debug/design`에서 한 화면으로 본다.

## 토큰

정본은 `src/core/styles/globals.css`다. 화면과 컴포넌트는 역할 이름만 쓴다.

| 역할             | 유틸리티 예                       | 값               |
| ---------------- | --------------------------------- | ---------------- |
| 바탕, 면         | `bg-page`, `bg-surface`           | #ffffff          |
| 가라앉은 면      | `bg-surface-sunken`               | #f5f5f7          |
| 구분선           | `border-line`                     | #ececf0          |
| 입력 테두리      | `border-line-strong`              | #8a8a95          |
| 본문             | `text-fg`                         | #17171c          |
| 보조 글자        | `text-fg-muted`                   | #45454f          |
| 부가 정보        | `text-fg-subtle`                  | #6b6b76          |
| 주요 동작        | `bg-action`, `text-on-action`     | #17171c, #ffffff |
| 주요 동작 hover  | `bg-action-hover`                 | #2a2a32          |
| 오류와 삭제      | `text-danger`, `bg-danger-tint`   | #b42318, #fef3f2 |
| 주의와 숨김 표시 | `text-warning`, `bg-warning-tint` | #b54708, #fffaeb |
| 게임 버튼        | `bg-game`, `border-game-line`     | #ffd60a, #17171c |
| 초점 표시        | `outline-focus`                   | #17171c          |
| 삭제 버튼의 글자 | `text-on-danger`                  | #ffffff          |
| 대화상자 배경    | `bg-scrim`                        | 먹색 40%         |

원색(`--ink-900` 등)은 `:root`의 CSS 변수로만 있고 Tailwind 유틸리티로는 생기지 않는다.

### 감정 색

몬스터 그림에서 뽑았다. `data-emotion` 속성이 붙은 요소 안에서 `bg-emotion`, `bg-emotion-tint`, `text-emotion-fg`가 그 감정의 값이 된다. 속성이 없으면 회색이다.

| 감정                        | 진한 색 | 옅은 색 | 글자 색 |
| --------------------------- | ------- | ------- | ------- |
| 불안 `ANXIETY`              | #6d28d9 | #f1ebff | #5334b8 |
| 무기력 `LETHARGY`           | #7187e0 | #e9edfc | #3346a3 |
| 외로움 `LONELINESS`         | #45c6dd | #e0f5fa | #0d6379 |
| 자기비하 `SELF_DEPRECATION` | #a3b03c | #f0f3de | #56621d |
| 짜증 `IRRITATION`           | #cf2f38 | #fdeaea | #a81f26 |

감정 색은 감정을 나타낼 때만 쓴다. 오류와 삭제에는 `danger`를 쓴다.

진한 색 다섯 가지는 차트에서 나란히 놓여도 구분되게 잡았다. 차트 색 검증기에서 이 순서로 인접한 쌍의 차이가 색각 이상에서 ΔE 14.5, 일반 시야에서 17.4다(기준 8과 15). 처음 값은 자기비하와 짜증이 적록 색각에서 2.2였다. 밝은 바탕과의 대비가 3:1에 못 미치는 색이 있어, 차트는 언제나 이름과 숫자를 함께 쓰고 낭독용 표를 둔다. 값을 바꾸면 검증기를 다시 돌린다.

## 글자

Pretendard 가변 글꼴이다. 굵기는 400, 500, 700, 800만 쓴다.

| 유틸리티       | 크기 / 줄 높이 | 쓰임                             |
| -------------- | -------------- | -------------------------------- |
| `text-caption` | 12 / 16        | 감정 태그, HP 숫자               |
| `text-label`   | 13 / 18        | 작성자 정보, 시각                |
| `text-body-sm` | 15 / 24        | 댓글, 버튼                       |
| `text-body`    | 16 / 26        | 피드 본문, 입력                  |
| `text-body-lg` | 17 / 29        | 글 상세 본문                     |
| `text-title`   | 20 / 28        | 구역 제목                        |
| `text-heading` | 24 / 32        | 화면 제목                        |
| `text-display` | 32 / 40        | 레이드 보스 이름, 로그인 소개 글 |
| `text-numeric` | 36 / 40        | 레이드 HP                        |

숫자는 `tabular-nums`로 쓴다. 작성 시각은 `formatRelativeTime`으로 "12분 전"처럼 쓰고, 일주일이 지나면 날짜로 쓴다.

## 모양과 움직임

- 모서리: `rounded-inline` 8px, `rounded-control` 14px(버튼, 입력, 썸네일), `rounded-surface` 20px(시트, 큰 면), `rounded-full`(칩, 태그).
- 높이: 칩 36px, 버튼 44px, 입력과 큰 버튼 52px, 게임 버튼 56px. 누르는 영역은 44px 이상이다.
- 기본은 1px 테두리이고 그림자는 쓰지 않는다. 게임 요소만 2px 외곽선과 `shadow-game`을 쓴다.
- 움직임의 시간은 세 단계다: `--duration-fast` 120ms(누름과 hover), `--duration-base` 200ms(값이 바뀌는 막대와 반응), `--duration-slow` 320ms(화면이 바뀌는 것).
- 몬스터의 대기 움직임은 `monster-idle-*`다(ADR-0006). `prefers-reduced-motion`이면 움직이지 않고, 눌린 게임 버튼도 자리를 옮기지 않는다.
- 좁은 화면의 메뉴는 위쪽 모서리가 둥근(`rounded-t-surface`) 막대다. 그림자 없이 테두리로 본문과 나눈다. 다섯 칸으로 나눠 고민 쓰기를 정가운데 칸에 두고, 주요 동작 색의 56px 원으로 막대 위에 올린다. 원의 둘레는 면 색 테두리로 본문과 나눈다. 넓은 화면의 메뉴에서는 다른 메뉴와 같다.
- 아이콘은 Phosphor다. 기본은 선, 선택된 메뉴와 누른 공감은 `weight="fill"`이다. 글자와 함께 있는 아이콘은 `aria-hidden`으로 둔다.

## 컴포넌트

| 컴포넌트                                            | 위치               | 쓰임                                                                   |
| --------------------------------------------------- | ------------------ | ---------------------------------------------------------------------- |
| `Button`                                            | `shared/ui`        | `primary`, `secondary`, `subtle`, `ghost`, `danger`                    |
| `GameButton`                                        | `shared/ui`        | 글 상세의 공감 보내기, 레이드의 공격하기에만                           |
| `IconButton`                                        | `shared/ui`        | 아이콘만 있는 버튼. `aria-label` 필수                                  |
| `Chip`                                              | `shared/ui`        | 필터와 선택. `pressed`로 상태를 전한다                                 |
| `TextField`, `Textarea`, `Input`, `Select`, `Label` | `shared/ui`        | 입력. 모두 52px이고 오류가 난 칸은 `aria-invalid`로 빨간 테두리가 된다 |
| `ConfirmDialog`, `Toaster`                          | `shared/ui`        | 대화상자와 알림 토스트                                                 |
| `Card`                                              | `shared/ui`        | 폼과 안내를 담는 면. 목록에는 쓰지 않는다                              |
| `Spinner`, `ListSkeleton`                           | `shared/ui`        | 불러오는 중. 둘 다 "불러오는 중"을 글자로 읽힌다                       |
| `BrandMark`                                         | `shared/ui`        | 머리말의 로고                                                          |
| `ReactionIcon`, `SelectionIndicator`                | `shared/ui`        | 공감 하트의 반응, 탭의 선택 선                                         |
| `EmotionTag`                                        | `entities/monster` | 점과 감정 이름                                                         |
| `HpBar`                                             | `entities/monster` | `sm`(피드), `lg`(글 상세와 레이드)                                     |
| `MonsterThumb`                                      | `entities/monster` | 48px 몬스터                                                            |
| `MonsterSummary`                                    | `entities/monster` | 목록용 요약: 썸네일, HP 숫자, 작은 HP 막대                             |
| `MonsterDisplay`                                    | `entities/monster` | 무대에 올리는 큰 몬스터. `detail`(글 상세), `boss`(레이드)             |
| `MonsterStage`                                      | `entities/monster` | 감정 색으로 채운 무대                                                  |

## 접근성

- 화면마다 제목이 다르다. 경로의 `metadata.title`이 "레이드 | 오구오구"처럼 붙는다. 클라이언트 페이지는 같은 폴더의 `layout.tsx`가 제목을 낸다.
- 화면에는 `h1`이 하나 있다. 글 상세처럼 보이는 제목이 없으면 `sr-only`로 둔다.
- 조용히 바뀌는 내용은 처음부터 그려 둔 `role="status"` 자리에 글자를 채운다. 내용이 든 채로 새로 생긴 자리는 읽히지 않는 일이 많다.
- 누른 버튼이 사라지는 곳(재검토 요청, 신고, 댓글 고치기)은 초점을 남은 안내나 버튼으로 옮긴다. 요청하는 동안에는 `disabled` 대신 `aria-disabled`로 막아 초점을 지킨다.
- `aria-label`은 역할이 있는 요소에만 붙이고, 보이는 글자가 있으면 그 글자를 이름에 그대로 담는다.
- 상담 전화 링크는 44px이고 이름에 기관 이름이 들어간다. 도움 안내 토스트는 닫을 때까지 남는다.
- 초점 표시는 공통 외곽선을 쓴다. `outline-none`에 `ring`으로 바꿔 그리지 않는다(고대비 모드에서 사라진다).

## 게임 요소를 쓰는 곳

`GameButton`과 큰 `HpBar`는 글 상세의 몬스터 무대와 레이드에만 쓴다. 위기 안내, 숨김 표시, 신고, 재검토 요청에는 게임 요소와 감정 색을 쓰지 않는다.

## 지키는 검사

화면을 모두 옮겼다. 아래는 테스트가 지킨다.

- 소스에 옛 토큰 이름, Tailwind 기본 색, 색을 직접 적은 임의 값, Lucide가 없다(`src/core/styles/token-usage.test.ts`).
- 글자와 바탕으로 함께 쓰는 토큰 쌍의 대비가 4.5:1 이상이다(`src/core/styles/tokens.test.ts`).
- 이 문서에 적힌 색 값이 `globals.css`에 있다.
- `GameButton`은 글 상세의 공감, 레이드의 공격, 카탈로그에서만 쓴다. 위기 안내와 신고, 재검토 요청은 게임 요소와 감정 색을 쓰지 않는다.
- 차트의 감정 색(`EMOTION_COLORS`)이 `globals.css`의 감정 색과 같다.

## 에셋과 출처

| 에셋          | 위치                                                             | 출처 / 라이선스                                                                                          |
| ------------- | ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| 감정 몬스터   | `public/monsters`                                                | 이 서비스를 위해 만든 그림(ADR-0006)                                                                     |
| 오구 캐릭터   | `public/brand/ogu-*.webp`                                        | 이 서비스를 위해 만든 그림. 몬스터와 같은 굵은 외곽선과 평평한 채색이고, 보라색은 쓰지 않는다(불안의 색) |
| 로고와 아이콘 | `public/brand/ogu-symbol.webp`, `public/pwa`, `src/app/icon.png` | 오구의 얼굴. 아이콘은 먹색(#17171c) 타일 위에 둔다                                                       |
| 한글 글꼴     | npm `pretendard`                                                 | [Pretendard](https://github.com/orioncactus/pretendard), SIL Open Font License 1.1                       |
| 아이콘        | npm `@phosphor-icons/react`                                      | [Phosphor](https://phosphoricons.com), MIT                                                               |
| 구글 로그인 G | `public/brand/google.png`                                        | [Google 로그인 브랜딩](https://developers.google.com/identity/branding-guidelines). 원본 색과 비율 유지  |
