# Architecture

This project organizes `src/` with [Feature-Sliced Design](https://feature-sliced.design)
(FSD) on top of the Next.js App Router, and leans on
[Toss's Frontend Fundamentals](https://github.com/toss/frontend-fundamentals)
guide for the code-quality conventions inside each slice. It was bootstrapped
by studying two references directly:

- **[seungmanchoi/nextjs-fsd-agent-template](https://github.com/seungmanchoi/nextjs-fsd-agent-template)**
  — the layer layout (`core` standing in for FSD's `app` layer, since Next.js
  owns the real `app/` directory) and the general tech-stack shape
  (TanStack Query, Zustand, RHF+Zod, axios with token refresh) come from here.
- **[toss/frontend-fundamentals](https://github.com/toss/frontend-fundamentals)**
  — the four questions this repo's code tries to answer at every layer:
  is it **readable** without extra context, is its behavior **predictable**,
  do things that change together **live** together (cohesion), and are slices
  **decoupled** enough to change independently.

## Layers

```
src/
├── app/        Next.js App Router — routes, layouts, API route handlers.
│                 Thin composition only: no business logic, no fetching logic
│                 beyond what a Server Component naturally does.
├── core/        FSD's "app" layer, renamed to avoid colliding with Next's
│                 own app/ directory. Global providers (TanStack Query,
│                 session bootstrap) and global styles. Nothing here is
│                 domain-specific.
├── widgets/     Independent, composed UI blocks (Header, TodoBoard). This is
│                 where features and entities get wired together — features
│                 never import each other directly.
├── features/    One user action per slice (login-form, logout, create/
│                 toggle/delete a todo). Depends on entities + shared only.
├── entities/    Domain nouns (user, session, todo): types, the canonical
│                 read query, and dumb/presentational UI.
└── shared/      Zero domain knowledge. UI kit, the axios client + refresh
                  interceptor, typed env vars, generic utils.
```

Imports only ever point downward: `app → core → widgets → features →
entities → shared`. Slices within the same layer do not import each other
directly — compose them one layer up instead (that's what `widgets/todo-board`
is for).

Every slice/segment re-exports its public surface through its own
`index.ts`. Deep imports like `@/shared/api/http-client` are forbidden —
import `@/shared/api` instead. This is enforced automatically by
[`steiger`](https://github.com/feature-sliced/steiger), the official FSD
architecture linter (`pnpm lint:fsd`), not by convention alone.

`steiger.config.ts` documents two intentional deviations from the default
rule set:

- `fsd/public-api` and `fsd/no-segmentless-slices` are off for `shared/`,
  since shared has segments but no slices.
- `fsd/insignificant-slice` is off for `features/` and `entities/`: this
  template keeps todo/auth actions one-concept-per-slice on purpose, even
  though each currently has a single consumer, to demonstrate the intended
  granularity. Merge a slice into its consumer once you've confirmed no
  second consumer is coming — don't pre-merge just because the linter
  would prefer fewer files today.

## State: server vs. client

- **Server state** (anything that lives on a backend: the todo list, the
  current user) is owned by **TanStack Query**, colocated with its entity
  (`entities/todo/api/queries.ts`). Mutations that represent a specific user
  action live in the feature that performs them
  (`features/todos/toggle/api/use-toggle-mutation.ts`), with optimistic
  updates where the UX benefits from it.
- **Client-only UI state** that doesn't need a store (form state, toggles)
  stays in component state or React Hook Form. **Zustand** is reserved for
  state that's genuinely global and not server data — here, that's just
  auth status (`entities/session`).

This split is deliberate: reaching for Zustand (or Redux) to cache server
data is a common source of the exact staleness/coupling bugs Frontend
Fundamentals' cohesion and predictability sections warn about.

## Auth flow

The mock backend (`src/app/api/**`, backed by `shared/server`) implements a
short-lived bearer access token (kept in `localStorage` via
`shared/lib/token-storage`) plus a long-lived **httpOnly** refresh cookie.
`shared/api/http-client.ts` attaches the access token to every request and,
on a 401, transparently calls `/auth/refresh` once, retries the original
request, and de-duplicates concurrent refreshes into a single in-flight
call so a burst of parallel requests doesn't trigger a refresh storm.
`src/proxy.ts` (Next.js 16's replacement for `middleware.ts`) gates
`/todos` server-side by checking for the refresh cookie before the page
ever renders.

This is a template's mock backend, not a real auth system — swap
`shared/server/fake-jwt.ts` and `shared/server/mock-db.ts` for your actual
identity provider and database.

## Recent-practice choices worth calling out

- **Next.js 16 / React 19**, App Router, Turbopack builds.
- **`proxy.ts`**, not `middleware.ts` — Next.js 16 deprecated the latter.
- **`steiger`** for architecture linting instead of a hand-rolled
  `eslint-plugin-boundaries` config — it's the FSD team's own tool and
  understands segments, public APIs, and slice significance out of the box.
- **Vitest + Testing Library** for unit/component tests, **Playwright** for
  the one true end-to-end path (login → list → create → toggle → delete),
  rather than Jest.
- **`@t3-oss/env-nextjs`** for env vars that fail fast and are typed, instead
  of raw `process.env.X!` scattered around.
- **httpOnly refresh cookie + in-memory/localStorage access token**, the
  pattern most write-ups from companies running large Next.js apps converge
  on for balancing XSS/CSRF risk against not needing a backend session store.
- **pnpm** as the package manager.
