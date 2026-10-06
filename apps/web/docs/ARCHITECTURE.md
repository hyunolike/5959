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

## 실시간 알림 (004-notification-mypage)

내 글에 댓글이나 공감이 달리거나 몬스터가 생기고 처치되면, 새로고침 없이 알림 종의 배지와
토스트로 알린다. 알림 목록 화면(`/notifications`)은 US2에서 들어온다.

### 슬라이스

- `entities/notification`: 타입, 알림 문구(`notificationMessage`), 배지 글자(`badgeLabel`), 안 읽은 수 조회(`useUnreadCountQuery`).
- `features/notification-stream`: 티켓 발급과 `EventSource`(`api/connect.ts`), 연결 상태 스토어(`model/store.ts`), 대기 시간(`model/backoff.ts`), 캐시 반영(`model/cache-sync.ts`).
- `widgets/notification-bell`: 종과 배지, 토스트. 마운트된 동안 연결을 열어 둔다.

### 연결

브라우저는 같은 출처 `POST /api/notifications/stream-ticket`에서 일회용 티켓과 `streamUrl`을
받고, `EventSource`로 API 도메인의 스트림에 바로 붙는다. 브라우저가 같은 출처 밖을 부르는
곳은 이 주소 하나뿐이다(research R3). 주소에는 `ticket`과 `lastEventId`만 싣고 access 토큰은
싣지 않는다. 범용 프록시는 `notifications/stream-tickets`를 404로 막는다.

- 처음에는 안 읽은 수 응답의 `latestSeq`를 `lastEventId`로 넘긴다. 다시 붙을 때는 마지막으로 받은 이벤트 id를 넘기고, 서버가 그 뒤의 알림을 다시 보낸다.
- 티켓은 한 번만 쓸 수 있어서 `EventSource`의 자동 재연결을 쓰지 않는다. `error`가 오면 바로 닫고 새 티켓으로 다시 연다. 서버가 15분 뒤 닫는 것도 같은 경로다.
- 다시 열기 전에 1초, 2초, 4초로 늘려 최대 30초까지 기다리고 ±20% 흔든다. 열리면 처음부터 다시 센다. 기다리는 중에 탭이 다시 보이거나 네트워크가 돌아오면 바로 붙는다.
- 티켓 발급이 401이면(세션 끝) `stopped`로 두고 다시 시도하지 않는다.
- 탭 하나에 연결은 하나다. 알림 종이 사라지거나 로그아웃에 성공하면 닫는다.

### 상태

Zustand 스토어에는 연결 상태(`idle`, `connecting`, `open`, `retrying`, `stopped`)와 마지막 이벤트
id, 연달아 실패한 횟수만 둔다. 알림과 안 읽은 수는 TanStack Query 캐시에 있다
(`["notifications", "list"]`, `["notifications", "unread-count"]`). `notification` 이벤트는 목록에서
같은 알림 ID를 지우고 첫 쪽 맨 앞에 넣는다. 공감 묶음은 같은 ID가 새 번호로 다시 오기
때문이다. 안 읽은 수는 이벤트에 실린 값으로 바꾼다. `unread-count` 이벤트는 배지만 바꾼다.

### 종이 보이는 조건

루트 레이아웃(`app/layout.tsx`)이 `ogu_ob` 쿠키가 있을 때만 알림 종을 그린다. 클라이언트
이동만으로는 루트 레이아웃이 다시 그려지지 않으므로, 로그인과 온보딩, 로그아웃은 이동한 뒤
`router.refresh()`로 레이아웃을 새로 받는다. 로그아웃 버튼을 두는 화면은
`onLoggedOut={stopNotificationStream}`으로 연결을 바로 닫는다(features끼리는 서로 가져다 쓰지
않으므로 화면이 이어 준다).

### 토스트

문구는 알림 종류, 행동한 회원의 닉네임, 묶인 인원 수로만 만든다. 글이나 댓글 본문은 넣지
않는다(ADR-0005). 누르면 관련 글로 간다. 알림 페이지를 보고 있으면 띄우지 않는다.

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
