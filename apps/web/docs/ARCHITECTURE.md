# Architecture

This app organizes `src/` with [Feature-Sliced Design](https://feature-sliced.design)
(FSD) on top of the Next.js App Router, and leans on
[Toss's Frontend Fundamentals](https://github.com/toss/frontend-fundamentals)
guide for the code-quality conventions inside each slice. It started from a
template bootstrapped by studying two references directly:

- **[seungmanchoi/nextjs-fsd-agent-template](https://github.com/seungmanchoi/nextjs-fsd-agent-template)**
  — the layer layout (`core` standing in for FSD's `app` layer, since Next.js
  owns the real `app/` directory) and the general tech-stack shape
  (TanStack Query, Zustand, RHF+Zod) come from here.
- **[toss/frontend-fundamentals](https://github.com/toss/frontend-fundamentals)**
  — the four questions this repo's code tries to answer at every layer:
  is it **readable** without extra context, is its behavior **predictable**,
  do things that change together **live** together (cohesion), and are slices
  **decoupled** enough to change independently.

## Layers

```
src/
├── app/        Next.js App Router — routes, layouts, API route handlers.
│                 Thin composition only: no business logic beyond what a
│                 Server Component naturally does, and no calls to the
│                 backend origin outside a route handler.
├── core/        FSD's "app" layer, renamed to avoid colliding with Next's
│                 own app/ directory. Global providers (TanStack Query)
│                 and global styles. Nothing here is domain-specific.
├── widgets/     Independent, composed UI blocks: `feed-list`,
│                 `post-detail`, `post-editor`, `service-status`. This is
│                 where features and entities get wired together —
│                 features never import each other directly.
├── features/    One user action per slice: `auth`, `onboarding`,
│                 `write-post`, `manage-post`, `like`, `comment`.
│                 Depends on entities + shared only.
├── entities/    Domain nouns: types, the canonical read query, and dumb/
│                 presentational UI: `member`, `post`, `comment`, `monster`.
└── shared/      Zero domain knowledge. UI kit, the API client
                  (`fetchApiHealth`, the TanStack Query client factory,
                  `ApiError`), typed env vars, generic utils.
```

Imports only ever point downward: `app → core → widgets → features →
entities → shared`. Slices within the same layer do not import each other
directly — compose them one layer up instead.

Every slice/segment re-exports its public surface through its own
`index.ts`. Deep imports like `@/shared/api/api-health` are forbidden —
import `@/shared/api` instead. This is enforced automatically by
[`steiger`](https://github.com/feature-sliced/steiger), the official FSD
architecture linter (`pnpm lint:fsd`), not by convention alone.

`steiger.config.ts` documents one intentional deviation from the default
rule set: `fsd/public-api` and `fsd/no-segmentless-slices` are off for
`shared/`, since shared has segments but no slices.

## State: server vs. client

- **Server state** (anything that lives on a backend) is owned by
  **TanStack Query**, colocated with the entity or widget that reads it
  (e.g. `widgets/service-status/api/use-api-health-query.ts`). Mutations
  that represent a specific user action live in the feature that performs
  them, with optimistic updates where the UX benefits from it.
- **Client-only UI state** that doesn't need a store (form state, toggles)
  stays in component state or React Hook Form. **Zustand** is reserved for
  state that's genuinely global and not server data.

This split is deliberate: reaching for Zustand (or Redux) to cache server
data is a common source of the exact staleness/coupling bugs Frontend
Fundamentals' cohesion and predictability sections warn about.

## Auth: BFF pattern

Auth follows the BFF (Backend-for-Frontend) pattern —
see [ADR-0002](../../../docs/adr/0002-bff-auth.md) for the decision record.
The browser never calls the backend origin directly; it only calls
same-origin `/api/*` route handlers under `src/app/api/**`. A route handler
reads the server-only `API_ORIGIN` env var (`shared/config`, validated with
`@t3-oss/env-nextjs`) and forwards the request to `apps/api`. `/api/health`
(`src/app/api/health/route.ts`) is the first such route: it calls
`shared/api`'s `fetchApiHealth(env.API_ORIGIN)` and returns the result as
JSON, so the browser only ever talks to its own origin.

### Auth routes

Dedicated route handlers under `src/app/api/auth/**` own the token/cookie
translation (`src/app/api/auth/signup`, `login`, `logout`, `onboarding`,
`oauth/[provider]`, `oauth/[provider]/callback`). Each one calls the API,
sets or clears cookies via `shared/server/auth-cookies.ts`, and strips
`accessToken`/`refreshToken` out of the JSON body before it reaches the
browser. The full request/response shape per route is in
[`contracts/bff-routes.md`](../../../specs/002-auth/contracts/bff-routes.md).

The general-purpose proxy, `src/app/api/[...path]/route.ts`, forwards
everything else to `API_ORIGIN` as `Authorization: Bearer <ogu_at>` plus
`X-Ogu-Bff-Key` and `X-Ogu-Client-Ip`. It refuses to forward two path
shapes so token-bearing responses never leak to the browser: any path whose
first segment is `auth` (`/api/auth/**`) and `/api/members/me/onboarding`
— both are served only by the dedicated routes above, which already scrub
tokens from the body. It also refuses `/api/notifications/stream-tickets`:
stream tickets come only from `POST /api/notifications/stream-ticket`. It also validates every path segment (rejects empty,
`.`, `..`, or segments containing `/`, `\`, `?`, `#`, including after a
second percent-decode) before re-encoding and calling `API_ORIGIN`, to stop
path-traversal or double-encoded-slash tricks from reaching an unintended
API path. On a session-only `401` (`UNAUTHORIZED`, `SESSION_EXPIRED`) it
calls refresh once via `shared/server/session-refresh.ts`
(`callWithSessionRefresh`) and retries the original request; other `401`
codes pass through untouched. `PUT /api/auth/onboarding` shares the same
refresh helper.

### Cookies

Session state lives entirely in three `httpOnly`, `Secure`, `SameSite=Lax`
cookies with the `__Host-` prefix (`shared/server/auth-cookies.ts`), so
subdomains cannot shadow them and client-side scripts cannot read them
(US4-AC6):

| Cookie          | Holds           | Notes                                                                                                                       |
| --------------- | --------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `__Host-ogu_at` | access token    | 15-minute max-age                                                                                                           |
| `__Host-ogu_rt` | refresh token   | max-age tracks the session's absolute expiry; untouched when a refresh response's `refreshToken` is `null` (rotation grace) |
| `__Host-ogu_ob` | onboarding flag | set/cleared alongside the other two as onboarding state changes                                                             |

The OAuth start route also sets a short-lived (10 min), HMAC-signed
`__Host-ogu_oauth` cookie carrying `state`, PKCE `code_verifier` (Google
only), and the sanitized `next` path; `shared/server/oauth-state.ts` verifies
the signature on callback. The signing key comes from `OAUTH_STATE_SECRET`,
which `shared/config/env.ts` requires whenever `VERCEL_ENV` is set (any
Vercel deployment, previews included) or `APP_ENV=production` — a
Vercel/production boot without it fails validation rather than falling back
to the development-only secret in `shared/server/oauth-secret.ts`.

### Route guards

`src/shared/server/route-guard.ts` holds the pure decision table
(`resolveRouteGuardAction`) that the routing layer (`proxy.ts`) applies
before a protected page renders, so an unauthenticated visit to a protected
path never flashes page content (US4-AC4): `/`, `/onboarding`, `/login`,
`/signup`, and the protected prefixes `/home`, `/write`, `/post`, `/my`, `/settings`
each redirect based on whether `ogu_rt` and `ogu_ob` are present. A
validated `next` query param (via `sanitizeNextPath`) sends the visitor back
to where they started after login (US4-AC5).

## 핵심 루프 (003-core-loop)

고민 글을 쓰고, 감정 분석으로 몬스터가 생기고, 공감과 댓글로 HP를 깎는 흐름이다.
페이지는 `/home`(피드), `/write`(글쓰기), `/post/[id]`(상세), `/post/[id]/edit`(수정)이고,
모두 로그인과 온보딩이 필요한 보호 경로다. 브라우저는 여기서도 같은 출처 `/api/*`만
부르고, 범용 프록시가 API의 `/api/v1/*`로 넘긴다.

### 슬라이스

- `entities/post`: 글 상세와 피드 조회(`usePostDetailQuery`, `useFeedQuery`), 피드 카드.
- `entities/comment`: 댓글 목록 조회와 댓글 한 줄.
- `entities/monster`: 외형 계산(`model/appearance.ts`), 정지 이미지 경로, 3D 장면과 몬스터 자리(`MonsterDisplay`).
- `features/write-post`, `features/manage-post`: 글 쓰기, 수정, 삭제.
- `features/like`, `features/comment`: 글 공감, 댓글 공감, 댓글 쓰기와 수정, 삭제. 낙관적 HP 계산이 여기 있다.
- `widgets/feed-list`, `widgets/post-detail`, `widgets/post-editor`: 위 슬라이스를 화면 단위로 조립한다.

### 쿼리 키와 폴링

쿼리 키 루트는 둘이다(`shared/config/constants.ts`). 글 상세는 `["posts", id]`, 댓글
목록은 그 아래 `["posts", id, "comments"]`이고, 피드는 따로 `["feed", filter]`다. 공격이
끝나면 `["posts", id]` 하나를 무효화해 상세와 댓글을 함께 서버 값으로 맞추고, 피드는
`["feed"]`로 낡은 것으로만 표시한다. 피드를 `["posts"]` 아래에 두지 않은 까닭은 상세를
무효화할 때 피드까지 다시 불러오지 않게 하려는 것이다.

상세는 몬스터가 생길 때까지 3초마다 다시 불러오고, 처음 불러온 지 2분이 지나면 15초로
늦춘다(research R10). 몬스터가 생기면 멈추고, 4xx(지운 글 404 등)를 받아도 멈춘다. 5xx와
네트워크 오류에는 멈추지 않는다. 글을 지우는 동안과 지운 뒤에도 멈춘다. 삭제
뮤테이션이 `["deletePost", id]` 키를 쓰고, 상세 쿼리가 이 키의 상태를 보고
`refetchInterval`을 끈다. 쿼리 자체(`enabled`)는 끄지 않는다. 성공한 삭제 뮤테이션은
5분(gcTime) 동안 캐시에 남아서, 쿼리를 끄면 그동안 같은 글을 다시 열 때 로딩만 보이기
때문이다. 폴링을 끄지 않으면 이동하기 전에 폴링이 한 번 더 돌아
"삭제된 글이에요"가 잠깐 보인다. 피드는 무한 스크롤이며 목록 끝 200px 앞에서 다음 쪽을
부르고, 인기순에서 같은 글이 두 쪽에 걸치면 처음 나온 자리에만 둔다.

### 낙관적 HP

공감과 댓글은 응답을 기다리지 않고 캐시의 HP를 먼저 줄인다(research R6). 감소량은
서버와 같아서 공감 1, 회원별 첫 댓글 3, 댓글 공감 1이고, 작성자 자신의 행동이나 이미
반영된 공격은 줄이지 않는다. 한 글을 공격하는 뮤테이션(글 공감과 취소, 댓글 공감과
취소, 댓글 쓰기)은 모두 `["attack", postId]` 뮤테이션 키를 쓴다. 공격이 겹치면 먼저 끝난
공격이 상세를 다시 불러와 아직 기다리는 공격의 낙관적 HP를 덮을 수 있어서,
`isMutating`으로 세어 마지막 공격이 끝날 때만 다시 불러온다. 실패하면 그 사이 서버 값이
들어오지 않았을 때만 되돌린다.

### 3D 몬스터와 정지 이미지

피드 카드는 언제나 정지 이미지(`public/monsters/{emotion}-{stage}.png`, 감정 5종에 HP
단계 4개로 20장)를 쓴다. 상세는 WebGL을 쓸 수 있고 움직임 줄이기가 꺼져 있을 때만 3D
장면을 그린다. three와 R3F는 `next/dynamic`으로만 불러와 첫 번들에 들어가지 않고(ADR-0003),
불러오는 동안에는 그 자리에 정지 이미지를 둔다. 장면은 몬스터가 화면에 보이고 움직이는
동안만 매 프레임 그린다. 쓰러졌거나 화면 밖에 있으면 HP가 바뀔 때처럼 필요할 때만 그린다.

3D 장면이 실패하면(렌더러를 만들지 못했거나 청크를 받지 못했을 때) `MonsterDisplay`의
오류 경계가 받아 정지 이미지로 바꾸고, 글 상세 전체는 오류 화면으로 넘어가지 않는다.
실패는 글마다 기억한다. 같은 글에서는 정지 이미지로 남고, App Router가 트리를 유지한 채
다른 글로 가면(`resetKey`가 바뀌면) 3D를 다시 시도한다.

정지 이미지는 `pnpm --filter web render:monsters`로 만든다. Vite로
`scripts/monster-capture` 하네스를 띄우고 Playwright Chromium이 3D 장면을 투명 배경
512×512로 찍는다. 이 스크립트는 Node의 타입 지우기에 기대므로 Node 22.18 이상이
필요하다. 외형이나 장면을 바꾸면 다시 돌려서 이미지를 커밋한다.

## 알림 (004-notification-mypage)

내 글에 댓글이나 공감이 달리거나 몬스터가 생기고 처치되면, 새로고침 없이 알림 종의 배지와
토스트로 알린다. 지난 알림은 목록 화면(`/notifications`)에서 보고 읽음으로 바꾼다.

### 슬라이스

- `entities/notification`: 타입, 알림 문구(`notificationMessage`), 배지 글자(`badgeLabel`), 안 읽은 수 조회(`useUnreadCountQuery`), 목록 조회(`useNotificationsQuery`), 쪽 펼치기(`uniqueNotifications`), 목록 한 줄(`NotificationItem`).
- `features/notification-stream`: 티켓 발급과 `EventSource`(`api/connect.ts`), 연결 상태 스토어(`model/store.ts`), 대기 시간(`model/backoff.ts`), 캐시 반영(`model/cache-sync.ts`).
- `features/read-notification`: 하나 읽음과 모두 읽음(`api/`), 캐시 반영과 `upToSeq` 계산(`model/`).
- `widgets/notification-bell`: 종과 배지, 토스트. 마운트된 동안 연결을 열어 둔다.
- `widgets/notification-list`: 목록 화면의 본문. 무한 스크롤, 모두 읽음, 삭제된 글 안내.

### 연결

브라우저는 같은 출처 `POST /api/notifications/stream-ticket`에서 일회용 티켓과 `streamUrl`을
받고, `EventSource`로 API 도메인의 스트림에 바로 붙는다. 브라우저가 같은 출처 밖을 부르는
곳은 이 주소 하나뿐이다(research R3). 주소에는 `ticket`과 `lastEventId`만 싣고 access 토큰은
싣지 않는다. 범용 프록시는 `notifications/stream-tickets`를 404로 막는다.

- 처음에는 안 읽은 수 응답의 `latestSeq`를 `lastEventId`로 넘긴다. 다시 붙을 때는 마지막으로 받은 이벤트 id를 넘기고, 서버가 그 뒤의 알림을 다시 보낸다.
- 티켓은 한 번만 쓸 수 있어서 `EventSource`의 자동 재연결을 쓰지 않는다. `error`가 오면 바로 닫고 새 티켓으로 다시 연다. 서버가 15분 뒤 닫는 것도 같은 경로다.
- 다시 열기 전에 1초, 2초, 4초로 늘려 최대 30초까지 기다리고 ±20% 흔든다. 20초 동안 열려 있어야 처음부터 다시 센다. 탭이 6개 이상이면 서버가 가장 오래된 연결을 닫는데, 열리자마자 되돌리면 탭끼리 1초마다 서로 밀어낸다. 기다리는 중에 탭이 다시 보이거나 네트워크가 돌아오면 바로 붙는다.
- 반쯤 죽은 연결은 `error`를 내지 않는다. 두 가지로 가려낸다. 서버가 25초마다 보내는 `ping` 이벤트를 비롯해 60초 동안 아무 이벤트도 없으면 닫고 다시 붙는다. 탭이 30초 넘게 가려졌거나 오프라인이었다 돌아오면 열린 연결도 닫고 바로 다시 붙는다.
- 티켓 발급이 401이면(세션 끝) `stopped`로 두고 기다렸다 다시 시도하지 않는다. 다른 탭에서 다시 로그인했을 수 있으므로, 탭이 다시 보이거나 초점을 받을 때 한 번만 붙어 본다. 실패하면 그대로 멈춰 있는다.
- 탭 하나에 연결은 하나다. 알림 종이 사라지거나 로그아웃에 성공하면 닫는다.

### 상태

Zustand 스토어에는 연결 상태(`idle`, `connecting`, `open`, `retrying`, `stopped`)와 마지막 이벤트
id, 연달아 실패한 횟수만 둔다. 알림과 안 읽은 수는 TanStack Query 캐시에 있다
(`["notifications", "list"]`, `["notifications", "unread-count"]`). `notification` 이벤트는 목록에서
같은 알림 ID를 지우고 첫 쪽 맨 앞에 넣는다. 공감 묶음은 같은 ID가 새 번호로 다시 오기
때문이다. 안 읽은 수는 이벤트에 실린 값으로 바꾼다. `unread-count` 이벤트는 배지만 바꾼다.
안 읽은 수 조회 결과의 `latestSeq`가 캐시보다 작으면 늦게 도착한 옛 응답이라 버린다
(`unreadCountQueryOptions`).

### 종이 보이는 조건

루트 레이아웃(`app/layout.tsx`)이 `ogu_ob` 쿠키가 있을 때만 알림 종을 그린다. 클라이언트
이동만으로는 루트 레이아웃이 다시 그려지지 않으므로, 로그인과 온보딩, 로그아웃은 이동한 뒤
`router.refresh()`로 레이아웃을 새로 받는다. 로그아웃 버튼을 두는 화면은
`onLoggedOut={stopNotificationStream}`으로 연결을 바로 닫는다(features끼리는 서로 가져다 쓰지
않으므로 화면이 이어 준다).

### 토스트

문구는 알림 종류, 행동한 회원의 닉네임, 묶인 인원 수로만 만든다. 글이나 댓글 본문은 넣지
않는다(ADR-0005). 누르면 그 알림을 읽음으로 바꾸고 관련 글로 간다(목록에서 누른 것과 같다).
글이 지워졌으면 이동하지 않고 닫히기만 한다. 알림 페이지를 보고 있으면 띄우지 않는다.

### 목록과 읽음

목록은 `useInfiniteQuery`로 20개씩 받고, 실시간 이벤트가 고치는 것과 같은 캐시
(`["notifications", "list"]`)를 쓴다. 화면을 열 때마다 다시 받는다(`refetchOnMount: "always"`).

- 쪽을 펼칠 때 같은 알림 ID는 한 번만 둔다(`uniqueNotifications`). 공감 묶음은 쪽 사이에 갱신되면 뒤쪽에서 빠지고, 실시간 이벤트가 맨 앞에 다시 넣는다.
- 목록을 받는 동안 온 이벤트는 캐시에 들어가지 못하거나 늦게 온 응답에 덮인다. 받은 목록의 가장 큰 번호가 스트림의 마지막 이벤트 id보다 작으면 한 번 다시 받는다. 같은 id로는 되풀이하지 않는다.
- 하나 읽음은 응답 전에 그 항목과 배지를 바꾸고, 실패하면 그 항목과 줄인 수만 되돌린다. 캐시를 통째로 덮지 않아서 그 사이 들어온 알림을 지우지 않는다. 끝나면 안 읽은 수를 다시 받는다(연결이 끊겨 `unread-count` 이벤트가 오지 않을 때를 위해서다).
- 모두 읽음은 `upToSeq`로 목록 첫 항목의 번호, 스트림의 마지막 이벤트 id, 안 읽은 수 응답의 `latestSeq` 가운데 큰 값을 보낸다. 응답이 오면 그 번호 이하만 읽음으로 바꾸므로 누르는 사이에 온 알림은 안 읽은 채 남는다. 마지막 이벤트 id는 다른 feature의 스토어에 있어서 위젯이 넘긴다.
- 글이 있는 항목은 `/post/{id}`로 가는 링크이고, 글이 지워진 항목(`post == null`)은 버튼이다. 버튼을 누르면 이동하지 않고 "삭제된 글이에요." 안내를 띄우고 읽음으로 바꾼다. 안내 문구는 글 상세의 404 안내와 같은 상수(`DELETED_POST_NOTICE`)다.
- 안 읽음은 색과 함께 "안 읽음" 글자로 보인다. 읽은 항목은 화면 낭독기에만 "읽음"을 읽어 준다. 모두 읽음 버튼은 누른 뒤에도 초점이 남도록 `disabled` 대신 `aria-disabled`로 막는다.

## 마이페이지 (004-notification-mypage)

`/my`는 프로필, 감정 통계(US4), 내 활동 탭(US3)으로 이루어진다. 세 탭은 내가 쓴 글, 내 댓글, 공감한 글이다.

### 슬라이스

- `entities/post`: `useMyPostsQuery`, `useLikedPostsQuery`. 응답은 피드와 같은 `FeedPage`라 `PostCard`를 그대로 쓴다. 마이페이지에서는 `showCreatedAt`으로 작성 시각도 보인다.
- `entities/comment`: `useMyCommentsQuery`, `MyCommentItem`(댓글 본문, 달린 글의 앞 50글자, 시각, 답글 표시).
- `widgets/my-activity`: 탭과 목록. `model/tab.ts`가 주소의 `?tab=posts|comments|likes`를 읽고 쓴다. 없거나 모르는 값이면 `posts`이고, 기본 탭은 검색어를 남기지 않는다. `ui/activity-list.tsx`는 세 탭이 같이 쓰는 목록 틀이다(불러오는 중, 실패, 빈 상태, 무한 스크롤).

- `widgets/emotion-stats-panel`: 감정 통계. 조회(`api/queries.ts`), 타입과 감정별 색(`model/types.ts`), 분포 막대와 8주 추이 차트(`ui/`)를 위젯 안에 둔다. 계획은 `entities/emotion-stats`였지만 쓰는 곳이 이 위젯 하나라 steiger의 `insignificant-slice`에 걸려 합쳤다. 감정 이름과 정지 이미지는 `entities/monster`의 `EMOTION_LABELS`, `MonsterSprite`를 쓴다.

### 프로필 수정

- `/my/edit`의 `features/edit-profile`이 닉네임, 직군, 경력을 고친다(US5). 폼은 지금 프로필로 채우고 바뀐 항목만 `PATCH /api/members/me`로 보낸다. 하나도 바꾸지 않았으면 저장 버튼을 막는다.
- 닉네임 규칙(`memberProfileSchema`), 안내 문구(`NICKNAME_REASON_LABEL`), 중복 확인(`useNicknameCheck`)은 `entities/member`에 있고 온보딩과 프로필 수정이 함께 쓴다. feature끼리는 서로 가져올 수 없어서 온보딩에 있던 것을 엔티티로 내렸다.
- 닉네임을 바꿨을 때만 중복 여부를 미리 확인한다. 내 닉네임의 대소문자만 바꾼 경우는 확인하지 않는다(확인 API는 내가 쓰는 닉네임도 사용 중이라고 답한다).
- 저장되면 내 정보 캐시를 응답으로 바꾸고 `["feed"]`, `["posts"]`, `["notifications", "list"]`, `["my"]`를 무효화한 뒤 `/my`로 간다. 닉네임이 보이는 화면이 바뀐 값으로 다시 받는다(US5-AC4).

### 감정 통계 차트

- 분포는 비율만큼 나눈 막대 하나와 범례다. 범례는 감정 5종을 고정 순서로 모두 보이고 이름, 수, 비율을 글자로 적는다. 색은 감정마다 고정이고(`EMOTION_COLORS`) 수에 따라 바뀌지 않는다.
- 8주 추이는 주마다 감정별로 쌓은 막대다. 높이는 가장 많은 주가 기준이고 빈 주는 0이다. 같은 값을 화면 낭독기용 표로도 둔다.
- 색만으로 값을 전하지 않는다. 바탕과 대비가 낮은 색이 있어서 숫자와 이름을 언제나 함께 보인다.
- 내 몬스터가 없으면 분포와 추이 대신 "아직 몬스터가 없어요"와 글쓰기를 보인다(US4-AC4). 함께 물리친 수는 남의 글에서 생기므로 그때도 보인다.

### 쿼리 키

`["my", "emotion-stats"]`, `["my", "posts"]`, `["my", "comments"]`, `["my", "liked-posts"]`. 공감, 댓글, 글 삭제는 다른 화면에서 일어나므로 그 뮤테이션들이 이 키를 무효화하지 않는다. 대신 네 쿼리 모두 `refetchOnMount: "always"`라 마이페이지나 탭을 열 때마다 서버 값으로 맞춘다. 고른 탭만 그리므로 목록도 그 탭을 열 때만 받는다.

### 빈 상태

탭에 항목이 없으면 안내와 "글쓰기"(`/write`), "피드 보기"(`/home`) 링크를 보인다(US3-AC4).

## 안전 (005-safety)

위기나 우려로 판정된 글과 댓글의 작성자에게 도움받을 곳을 안내하고, 다른 회원의 글과 댓글을 신고할 수 있게 한다.
판정, 숨김, 욕설 가리기는 모두 API가 한다. 웹은 응답에 실린 것만 그린다.

### 슬라이스

- `widgets/post-detail`: 도움 안내(`ui/safety-notice.tsx`)와 도움 리소스 조회(`api/use-support-resources-query.ts`). 계획은 `entities/safety`와 `widgets/safety-banner`였지만 쓰는 곳이 글 상세 하나라 steiger의 `insignificant-slice`에 걸려 위젯 안에 두었다.
- `features/report-content`: 신고 버튼과 대화상자. 사유 넷과, 기타일 때의 설명(200자).
- `features/request-review`: 숨겨진 내 글과 댓글의 재검토 요청 버튼.
- 두 feature는 쓰는 곳이 `widgets/post-detail` 하나라 `steiger.config.ts`에 `insignificant-slice` 예외를 두었다. 행동 하나에 feature 하나라는 배치를 지키려는 것이다.

### 도움 안내

- 응답에 `safety`가 있을 때만 그린다. `safety`는 작성자에게만 온다(글 상세, 댓글 목록의 내 댓글).
- 위기이거나 숨겨졌으면 닫을 수 없고, 우려면 접을 수 있다. 숨겨졌으면 다른 회원에게 보이지 않는다는 설명과 재검토 요청 버튼을 함께 보인다.
- 단계 이름("위기", "우려")은 화면에 쓰지 않는다. 판정을 통보하는 자리가 아니라 도움받을 곳을 알리는 자리다.
- 전화번호는 `tel:` 링크다. 도움 리소스 목록을 못 받아도 109 하나는 보인다.
- 댓글은 화면에 보이는 내 댓글의 `safety` 가운데 가장 무거운 것으로 안내 하나를 그린다. 재검토 요청은 숨겨진 내 댓글마다 그 댓글의 행동 줄에 둔다.

### 숨긴 글과 댓글

- 숨긴 글은 다른 회원에게 삭제된 글과 똑같이 보인다(404). 작성자의 목록에서는 `hidden`으로 "다른 회원에게 보이지 않아요" 표시를 단다.
- 숨긴 댓글은 다른 회원에게 작성자와 내용이 `null`로 온다. 자리만 남기고 답글은 그대로 그린다.

### 신고와 재검토 요청

- 신고 버튼은 다른 회원의 글과 댓글에만 둔다. 접수와 "이미 신고했어요"는 버튼 자리에, 한도 초과와 그 밖의 실패는 대화상자 안에 알린다. 서버는 내가 신고했는지를 알려 주지 않으므로 새로고침하면 버튼이 다시 보인다.
- 재검토 요청은 대상마다 한 번이다. 요청하면 버튼 자리가 안내로 바뀌고, 서버의 `safety.reviewRequested`가 그 상태를 이어 준다.
- 오류 수집 도구로 보내기 전에 `content`, `detail` 키를 지운다(`shared/lib/scrub-event.ts`). 글 본문과 신고 설명이 나가지 않는다.

### 알림

- 종류 셋이 늘었다: `SUPPORT_NOTICE`(도움 안내), `CONTENT_RESTORED`(숨김 해제), `REVIEW_KEPT`(재검토 결과 유지). 문구는 `entities/notification`의 `notificationMessage`에 있다.
- 도움 안내 토스트는 8초 보인다. 다른 토스트는 그대로다.

### 운영자

운영자 화면은 없다. BFF 프록시(`app/api/[...path]/route.ts`)는 `operator/**`를 API로 넘기지 않고 404로 끊는다. 브라우저 세션이 운영자 기능에 닿는 길을 두지 않는다.

## 레이드 (006-raid)

모든 회원이 보스 한 마리를 버튼으로 함께 공격한다. 다른 회원의 공격이 새로고침 없이 HP에 보인다.

### 슬라이스

- `entities/raid`: 타입, 조회(`useRaidQuery`), 합치기(`mergeRaidLive`, `mergeRaidAttack`, `mergeRaidFetch`), 실시간 이벤트를 캐시에 넣기(`applyRaidLive`). 화면은 없다.
- `features/raid-attack`: 공격 뮤테이션과 버튼. 쓰는 곳이 하나라 steiger 예외에 있다.
- `features/notification-stream`: 스트림이 함께 받을 주제(`setTopic`, `useStreamTopic`)와 주제 소식 전달(`onTopic`).
- `widgets/raid-arena`: 레이드 화면의 본문(`RaidArena`)과 홈의 보스 안내(`BossBanner`). 안내는 감정 이름과 그림(`entities/monster`)이 필요한데 엔티티끼리는 가져올 수 없어 위젯에 두었다.
- `app/raid/page.tsx`: 조립만. `/raid`는 라우트 가드의 보호 경로다.

### HP는 줄어들기만 한다

레이드 캐시(`["raid"]`) 하나를 공격 응답, 실시간 이벤트, 조회 응답이 함께 고친다. 셋이 어떤 순서로 와도 화면의 HP가 뒤로 돌아가지 않게, 넣기 전에 `entities/raid/model/merge.ts`로 합친다.

- 같은 보스면 HP는 작은 쪽, 참여자 수와 내 기여는 큰 쪽을 남긴다.
- `epoch`가 커졌으면 서버의 값이 기록에서 다시 채워진 것이다(Redis가 다시 떴다). 이때만 받은 값을 그대로 따른다.
- 한 번 끝난 보스는 늦게 온 소식으로 되살아나지 않는다.
- 보스가 바뀌었거나 끝났으면 이벤트만으로는 화면을 채울 수 없어 조회를 다시 한다(새 보스의 감정, 끝난 때, 다음 보스가 나오는 때).

### 실시간

- 레이드 소식은 알림 스트림으로 온다. 스트림을 따로 열지 않는다(탭마다 연결 하나).
- `RaidArena`가 보이는 동안 `useStreamTopic("raid")`가 주제를 더한다. 주제가 바뀌면 스토어가 연결을 닫고 마지막 이벤트 id와 새 주제로 다시 연다. 화면을 떠나면 주제를 뺀다.
- 연결이 열려 있지 않으면 3초마다 조회한다. 열려 있으면 조회하지 않는다.

### 공격 버튼

- 받아들여지면 응답의 `cooldownMs` 동안 잠근다. 잠금은 편의이고 판단은 서버가 한다. 서버가 429를 주면 그만큼 더 잠근다.
- 잠긴 동안에도 초점이 남도록 `disabled` 대신 `aria-disabled`로 막는다. 연달아 누르는 버튼이다.
- 보스가 끝났다는 응답이면 조회를 다시 해 결과 화면으로 넘어가고, 쉬는 중이라는 응답이면 캐시의 `available`을 내린다.

### 보이지 않는 것

순위, 다른 회원의 닉네임과 기여는 응답에도 화면에도 없다. 보이는 것은 참여자 수와 내 기여뿐이다.

## 비슷한 고민 추천 (007-recommend)

글 상세 아래에 이 글과 비슷한 다른 회원의 글을 최대 5개 보여 준다.

- `entities/post`: 조회(`useSimilarPostsQuery`)와 폴링 간격(`similarPollInterval`). 카드는 피드의 `PostCard`를 그대로 쓴다.
- `widgets/post-detail/ui/similar-posts.tsx`: 추천 구역. 근거(`basis`)가 `SIMILAR`면 "비슷한 고민", `SAME_EMOTION`이면 "같은 감정의 고민"이라 부른다. 같은 감정으로 고른 글을 비슷한 고민이라 부르지 않는다.
- 보여 줄 글이 없거나(`NONE`) 조회가 실패하면 구역을 그리지 않는다. 오류도 보이지 않는다. 추천은 덤이라 글 읽기를 방해하지 않는다.
- 글 상세와 따로 조회하고 캐시 키도 따로 둔다(`["similar-posts", id]`). 공감과 댓글이 상세를 무효화할 때 추천까지 다시 부르지 않는다.
- 응답의 `pending`이 true면 3초마다, 처음 불러온 때부터 30초까지 다시 부른다. 방금 쓴 글의 추천이 새로고침 없이 나타난다.
- 추천 구역은 글의 `article`("고민 글") 밖에 있다. 카드에도 몬스터와 HP가 있어, e2e에서 글의 것을 볼 때는 `e2e-full/support/detail.ts`의 `postArticle` 안에서 찾는다.

## Recent-practice choices worth calling out

- **Next.js 16 / React 19**, App Router, Turbopack builds.
- **`steiger`** for architecture linting instead of a hand-rolled
  `eslint-plugin-boundaries` config — it's the FSD team's own tool and
  understands segments, public APIs, and slice significance out of the box.
- **Vitest + Testing Library** for unit/component tests, **Playwright** for
  end-to-end tests, rather than Jest.
- **`@t3-oss/env-nextjs`** for env vars that fail fast and are typed, instead
  of raw `process.env.X!` scattered around.
- **BFF pattern**, not a client-held token — the browser never sees an
  access or refresh token; see [ADR-0002](../../../docs/adr/0002-bff-auth.md).
- **pnpm** as the package manager, invoked from the repo root as
  `pnpm --filter web <script>`.
