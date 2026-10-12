# 오구오구 디자인 시스템

직장인의 고민을 나누고 공감으로 감정 몬스터를 함께 물리치는 커뮤니티다. 피드는 글을 읽기 좋게, 글 상세와 레이드는 몬스터가 주인공이 되게 한다. 브랜드 색은 두지 않는다. 화면의 색은 다섯 감정에서만 나온다.

설계와 결정의 까닭은 `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md`에 있다. 토큰과 컴포넌트는 `ENABLE_DESIGN_CATALOG=1`로 띄운 `/debug/design`에서 한 화면으로 본다.

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

원색(`--ink-900` 등)은 `:root`의 CSS 변수로만 있고 Tailwind 유틸리티로는 생기지 않는다.

### 감정 색

몬스터 그림에서 뽑았다. `data-emotion` 속성이 붙은 요소 안에서 `bg-emotion`, `bg-emotion-tint`, `text-emotion-fg`가 그 감정의 값이 된다. 속성이 없으면 회색이다.

| 감정                        | 진한 색 | 옅은 색 | 글자 색 |
| --------------------------- | ------- | ------- | ------- |
| 불안 `ANXIETY`              | #8b5cf6 | #f1ebff | #5334b8 |
| 무기력 `LETHARGY`           | #7187e0 | #e9edfc | #3346a3 |
| 외로움 `LONELINESS`         | #35b6d3 | #e0f5fa | #0d6379 |
| 자기비하 `SELF_DEPRECATION` | #8a9a3c | #f0f3de | #56621d |
| 짜증 `IRRITATION`           | #e5484d | #fdeaea | #a81f26 |

감정 색은 감정을 나타낼 때만 쓴다. 오류와 삭제에는 `danger`를 쓴다. 불안과 무기력은 색이 가까우니 차트에서는 이름과 숫자를 함께 쓴다.

## 글자

Pretendard 가변 글꼴이다. 굵기는 400, 500, 700, 800만 쓴다.

| 유틸리티       | 크기 / 줄 높이 | 쓰임               |
| -------------- | -------------- | ------------------ |
| `text-caption` | 12 / 16        | 감정 태그, HP 숫자 |
| `text-label`   | 13 / 18        | 작성자 정보, 시각  |
| `text-body-sm` | 15 / 24        | 댓글, 버튼         |
| `text-body`    | 16 / 26        | 피드 본문, 입력    |
| `text-body-lg` | 17 / 29        | 글 상세 본문       |
| `text-title`   | 20 / 28        | 구역 제목          |
| `text-heading` | 24 / 32        | 화면 제목          |
| `text-display` | 32 / 40        | 레이드 보스 이름   |
| `text-numeric` | 36 / 40        | 레이드 HP          |

숫자는 `tabular-nums`로 쓴다. 작성 시각은 `formatRelativeTime`으로 "12분 전"처럼 쓰고, 일주일이 지나면 날짜로 쓴다.

## 모양과 움직임

- 모서리: `rounded-inline` 8px, `rounded-control` 14px(버튼, 입력, 썸네일), `rounded-surface` 20px(시트, 큰 면), `rounded-full`(칩, 태그).
- 높이: 칩 36px, 버튼 44px, 입력과 큰 버튼 52px, 게임 버튼 56px. 누르는 영역은 44px 이상이다.
- 기본은 1px 테두리이고 그림자는 쓰지 않는다. 게임 요소만 2px 외곽선과 `shadow-game`을 쓴다.
- 몬스터의 대기 움직임은 `monster-idle-*`다(ADR-0006). `prefers-reduced-motion`이면 움직이지 않는다.
- 아이콘은 Phosphor다. 기본은 선, 선택된 메뉴와 누른 공감은 `weight="fill"`이다. 글자와 함께 있는 아이콘은 `aria-hidden`으로 둔다.

## 컴포넌트

| 컴포넌트                                  | 위치               | 쓰임                                                |
| ----------------------------------------- | ------------------ | --------------------------------------------------- |
| `Button`                                  | `shared/ui`        | `primary`, `secondary`, `subtle`, `ghost`, `danger` |
| `GameButton`                              | `shared/ui`        | 글 상세의 공감 보내기, 레이드의 공격하기에만        |
| `IconButton`                              | `shared/ui`        | 아이콘만 있는 버튼. `aria-label` 필수               |
| `Chip`                                    | `shared/ui`        | 필터와 선택. `pressed`로 상태를 전한다              |
| `TextField`, `Textarea`, `Input`, `Label` | `shared/ui`        | 입력                                                |
| `ConfirmDialog`, `Toaster`                | `shared/ui`        | 대화상자와 알림 토스트                              |
| `EmotionTag`                              | `entities/monster` | 점과 감정 이름                                      |
| `HpBar`                                   | `entities/monster` | `sm`(피드), `lg`(글 상세와 레이드)                  |
| `MonsterThumb`                            | `entities/monster` | 피드의 48px 몬스터                                  |
| `MonsterStage`                            | `entities/monster` | 감정 색으로 채운 무대                               |

## 게임 요소를 쓰는 곳

`GameButton`과 큰 `HpBar`는 글 상세의 몬스터 무대와 레이드에만 쓴다. 위기 안내, 숨김 표시, 신고, 재검토 요청에는 게임 요소와 감정 색을 쓰지 않는다.

## 옮기는 중

화면은 아직 옛 이름(`neutral-*`, `brand-*`)을 쓰는 곳이 있다. `globals.css`가 옛 이름을 새 값에 이어 두었고, 아직 안 옮긴 파일은 `src/core/styles/legacy-style-files.json`에 있다. 파일을 옮기면 아래 명령으로 목록에서 뺀다.

```bash
UPDATE_LEGACY_BASELINE=1 pnpm --filter web exec vitest run src/core/styles/token-usage.test.ts
```

## 에셋과 출처

| 에셋          | 위치                             | 출처 / 라이선스                                                                                         |
| ------------- | -------------------------------- | ------------------------------------------------------------------------------------------------------- |
| 감정 몬스터   | `public/monsters`                | 이 서비스를 위해 만든 그림(ADR-0006)                                                                    |
| 오구 캐릭터   | `public/brand/ogu-companion.png` | 이 서비스를 위해 만든 그림                                                                              |
| 한글 글꼴     | npm `pretendard`                 | [Pretendard](https://github.com/orioncactus/pretendard), SIL Open Font License 1.1                      |
| 아이콘        | npm `@phosphor-icons/react`      | [Phosphor](https://phosphoricons.com), MIT                                                              |
| 구글 로그인 G | `public/brand/google.png`        | [Google 로그인 브랜딩](https://developers.google.com/identity/branding-guidelines). 원본 색과 비율 유지 |
