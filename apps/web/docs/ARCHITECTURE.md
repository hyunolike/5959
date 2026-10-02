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
├── widgets/     Independent, composed UI blocks. Currently just
│                 `service-status`. This is where features and entities
│                 will get wired together once they exist — features never
│                 import each other directly.
├── features/    One user action per slice. Empty until real domain work
│                 lands; depends on entities + shared only.
├── entities/    Domain nouns: types, the canonical read query, and dumb/
│                 presentational UI. Empty until real domain work lands.
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
tokens from the body. It also validates every path segment (rejects empty,
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
`/signup`, and the protected prefixes `/home`, `/write`, `/my`, `/settings`
each redirect based on whether `ogu_rt` and `ogu_ob` are present. A
validated `next` query param (via `sanitizeNextPath`) sends the visitor back
to where they started after login (US4-AC5).

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
