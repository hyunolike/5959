# Next.js FSD Template

[![CI](https://github.com/hyunolike/nextjs-fsd-template/actions/workflows/ci.yml/badge.svg)](https://github.com/hyunolike/nextjs-fsd-template/actions/workflows/ci.yml)
[![Next.js](https://img.shields.io/badge/Next.js-16-black?logo=next.js)](https://nextjs.org)
[![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org)
[![Architecture](https://img.shields.io/badge/architecture-Feature--Sliced%20Design-orange)](https://feature-sliced.design)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

A Next.js 16 (App Router) template organized with
[Feature-Sliced Design](https://feature-sliced.design), with a working
auth + CRUD flow wired end to end — not just an empty folder structure.

## Quick start

```bash
pnpm install
pnpm dev
```

Open [http://localhost:3000](http://localhost:3000) and sign in at `/login`
with the pre-filled demo credentials (`demo@example.com` / `password123`).
They're served by mock API routes in `src/app/api/**` backed by an
in-memory store (`src/shared/server`), so the app runs with **zero external
services** — no database, no auth provider to configure.

## Features

- 🏗️ **Feature-Sliced Design**, enforced by tooling, not just convention —
  `pnpm lint:fsd` runs [`steiger`](https://github.com/feature-sliced/steiger),
  the official FSD architecture linter, catching wrong-direction imports and
  public-API sidesteps
- 🔐 **Real auth flow**: httpOnly refresh cookie + short-lived bearer access
  token, with an axios interceptor that silently refreshes on 401 and
  de-dupes concurrent refresh calls
- 🔄 **Server state via TanStack Query** (with optimistic updates on
  toggle), **client state via Zustand** kept to the one thing that's
  genuinely global (auth status) — not a dumping ground for server data
- 🧾 **Forms with React Hook Form + Zod**, typed end to end
- 🚦 `src/proxy.ts` — Next.js 16's replacement for `middleware.ts` — gates
  protected routes server-side before they render
- ✅ **Vitest + Testing Library** for unit/component tests, **Playwright**
  for a real end-to-end login → create → toggle → delete flow
- 🪝 **Husky + lint-staged + commitlint** (Conventional Commits) and a
  GitHub Actions CI pipeline (typecheck, lint, `lint:fsd`, tests, build, e2e)

## Stack

| Concern              | Choice                                                  |
| -------------------- | ------------------------------------------------------- |
| Framework            | Next.js 16 (App Router, Turbopack, React 19)            |
| Language             | TypeScript 5, strict                                    |
| Styling              | Tailwind CSS 4                                          |
| Server state         | TanStack Query 5                                        |
| Client state         | Zustand 5 (auth status only)                            |
| Forms                | React Hook Form 7 + Zod 4                               |
| HTTP                 | axios, with a refresh-token interceptor                 |
| Env vars             | `@t3-oss/env-nextjs`                                    |
| Architecture lint    | `steiger` (official FSD linter)                         |
| Unit/component tests | Vitest + Testing Library                                |
| E2E tests            | Playwright                                              |
| Formatting           | Prettier + `prettier-plugin-tailwindcss`                |
| Git hooks            | Husky + lint-staged + commitlint (Conventional Commits) |
| Package manager      | pnpm                                                    |

## Folder structure

```
src/
├── app/        Next.js routes + API route handlers (thin composition only)
├── core/       Global providers, session bootstrap, global styles
├── widgets/    Composed UI blocks (Header, TodoBoard)
├── features/   One user action per slice (login-form, logout,
│                create/toggle/delete todo)
├── entities/   Domain nouns (user, session, todo)
└── shared/     UI kit, HTTP client, env config, generic utils — zero
                 domain knowledge
```

## Scripts

| Script                              | What it does                              |
| ----------------------------------- | ----------------------------------------- |
| `pnpm dev`                          | Start the dev server                      |
| `pnpm build` / `pnpm start`         | Production build / serve                  |
| `pnpm typecheck`                    | `tsc --noEmit`                            |
| `pnpm lint`                         | ESLint                                    |
| `pnpm lint:fsd`                     | `steiger` — FSD layer/public-API rules    |
| `pnpm format` / `pnpm format:check` | Prettier                                  |
| `pnpm test` / `pnpm test:watch`     | Vitest                                    |
| `pnpm test:e2e`                     | Playwright (builds + boots the app first) |

## Docs

- [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) — layer rules, why `core/`
  stands in for FSD's own `app` layer, the server/client state split, the
  auth flow, and which "recent trend" choices were made deliberately and why
- [`CONTRIBUTING.md`](./CONTRIBUTING.md) — how to add a new slice, the PR
  checklist, and the code-quality bar this repo holds itself to

## Acknowledgments

This template was built by studying two references directly, not just
loosely inspired by them:

- [seungmanchoi/nextjs-fsd-agent-template](https://github.com/seungmanchoi/nextjs-fsd-agent-template)
  for the layer layout and tech-stack shape
- [toss/frontend-fundamentals](https://github.com/toss/frontend-fundamentals)
  for the code-quality bar (readability, predictability, cohesion, coupling)
  applied inside each slice

## License

[MIT](./LICENSE)
