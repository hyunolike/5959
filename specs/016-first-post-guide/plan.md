# Implementation Plan: 첫 글로 이어지는 안내

**Branch**: `016-first-post-guide` | **Date**: 2026-10-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/016-first-post-guide/spec.md`

## Summary

가입한 사람이 첫 글까지 가도록 길을 잇는다. 온보딩은 직군과 경력을 묻는 까닭을 말하고, 마치면 홈 맨 위에 환영 안내가 한 번 보인다. 글이 없는 회원은 홈에서 첫 고민 카드를 본다. 쓰기 화면은 공개 범위를 한 묶음으로 보이고, 빈 입력란에 실마리를 주며, 말투마다 댓글 예시를 보인다. 첫 글을 올리면 앞으로 일어날 일을 안내하고, 남의 글 아래에는 "나도 털어놓기"를 둔다. 첫 화면과 둘러보기의 사용법 옆에는 실제 몬스터 그림으로 만든 소리 없는 반복 장면을 둔다.

웹만 바뀐다. API, 계약, DB는 그대로다. "내 글이 있는가"는 내 글 목록을 한 개만 받아 안다. 온보딩 뒤와 첫 글 뒤라는 한 번뿐인 순간은 각각 탭 저장소의 값과 주소의 쿼리로 넘기고, 받는 화면이 읽자마자 지운다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**: Next.js 16, React 19, TanStack Query 5, react-hook-form, Tailwind CSS 4, Phosphor Icons. 새 의존 없음

**Storage**: 서버 저장 없음. 브라우저의 sessionStorage(환영 안내 한 번), localStorage(첫 고민 카드 닫음)

**Testing**: Vitest + Testing Library / Playwright(`e2e`, `e2e-full`)

**Target Platform**: Cloud Run(웹, 병합하면 배포). API는 올리지 않는다

**Project Type**: 웹 서비스(모노레포 `apps/web`만)

**Performance Goals**: 첫 화면의 글이 처음 보이는 시점이 늦어지지 않는다(SC-006). 시각 자료는 CSS만 쓰고 그림은 늦게 받는다. 홈과 쓰기 화면은 요청이 하나(`size=1`) 늘 뿐이다

**Constraints**:
- 위험 판정이 있는 글에는 첫 글 안내를 보이지 않는다(FR-017, 헌법 IV)
- 몬스터 분석이 늦거나 실패해도 쓰기와 안내가 막히지 않는다(헌법 V)
- 브라우저 저장소가 막혀도 화면이 깨지지 않는다. 모든 접근은 예외를 잡는다
- 회원 흐름 e2e의 "온보딩 → `/home`"을 바꾸지 않는다(research R2)
- 움직임 줄이기, 화면 낭독기, 375px 폭에서 모두 쓸 수 있다

**Scale/Scope**: 인수 조건 31개(US1 7, US2 9, US3 6, US4 3, US5 6). 새 위젯 하나(`first-post-prompt`), 새 컴포넌트 넷(`PrivacyScope`, `MonsterLifecycleLoop`, `FirstPostGuide`, `WriteYourOwn`). 실마리 칩은 `PostForm` 안에 그린다, 새 쿼리 하나. 고치는 화면은 온보딩, 홈, 쓰기, 고치기, 글 상세, 첫 화면, 둘러보기

## Constitution Check

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 위젯은 `entities`, `features`만 쓴다. `MonsterLifecycleLoop`는 두 위젯이 함께 쓰므로 `entities/monster`에 둔다. 위젯끼리 부르지 않는다. steiger를 느슨하게 하지 않는다 | 통과 |
| II. 계약이 코드보다 먼저다 | API를 바꾸지 않는다. 화면 사이의 약속은 [contracts/ui-contract.md](contracts/ui-contract.md)에 먼저 적었다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 31개에 ID가 있다. 시각 자료가 "8초 안에 돈다"와 375px 확인은 quickstart의 수동 절차로 둔다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 위험 판정이 있으면 첫 글 안내 대신 도움 안내만 둔다. 실마리는 위기 표현과 감정 단정을 담지 않고, 실마리로 시작한 글도 위험 감지를 똑같이 거친다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI를 새로 부르지 않는다. 몬스터가 없어도 첫 글 안내는 보이고 몬스터 자리는 "분석 중"이다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 자원, 새 의존이 없다. 시각 자료는 CSS다 | 통과 |

위반이 없다. Complexity Tracking은 비어 있다.

**설계 뒤 다시 보기**: research와 UI 계약을 쓴 뒤에도 판정은 같다. 공개 범위 문구를 옮기면서 011 US3-AC1의 테스트 기대 문구가 바뀌지만 인수 조건 자체는 지킨다.

## Project Structure

### Documentation (this feature)

```text
specs/016-first-post-guide/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/ui-contract.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
apps/web/src/
├── shared/config/                         # QUERY_KEYS.hasMyPost
├── entities/post/
│   ├── api/use-has-my-post-query.ts       # size=1
│   └── model/types.ts                     # COMMENT_TONE_EXAMPLES
├── entities/monster/ui/monster-lifecycle-loop.tsx   # 서버 컴포넌트, CSS 반복과 멈춘 그림
├── features/onboarding/ui/onboarding-form.tsx       # 까닭 한 줄, welcome.pending 남기기
├── features/write-post/
│   ├── model/starters.ts                  # WRITE_STARTERS
│   ├── ui/privacy-scope.tsx
│   ├── ui/post-form.tsx                   # starters, 말투 예시, 입력란 설명
│   ├── ui/write-form.tsx                  # ?first=1, write-submit
│   └── api/use-create-post-mutation.ts    # hasMyPost 무효화
├── features/manage-post/                  # 지우면 hasMyPost 무효화
├── features/install-app/index.ts          # useInstallApp 내보내기
├── widgets/first-post-prompt/             # 새 위젯: welcome / nudge
├── widgets/post-detail/ui/
│   ├── first-post-guide.tsx
│   ├── write-your-own.tsx
│   └── post-detail.tsx                    # 자리 잡기, 몬스터 등장 문장 보이기
├── widgets/post-editor/ui/post-editor.tsx # PrivacyScope
├── widgets/landing/ui/landing.tsx         # 사용법 옆 시각 자료
├── widgets/guest-home/ui/guest-home.tsx   # 사용법 위로, 도감 카드 문장, 시각 자료
├── app/home/page.tsx                      # FirstPostPrompt
└── app/write/page.tsx                     # 머리말 정리, PrivacyScope
apps/web/e2e/landing.spec.ts, guest-home.spec.ts
apps/web/e2e-full/first-post-guide.spec.ts # 새로: 가입 → 환영 → 첫 글 → 안내
docs/features.md, README.md                # 기능 표에 한 줄
```

**Structure Decision**: 웹 앱 하나만 고친다. 두 위젯(첫 화면, 둘러보기)이 함께 쓰는 시각 자료는 몬스터 엔티티의 UI로 내리고, 홈에만 쓰는 첫 고민 카드와 환영 안내는 새 위젯으로 둔다.

## Complexity Tracking

없음.
