# Contributing

## Adding a new slice

1. Pick the lowest layer that fits: a new domain noun goes in `entities/`,
   a new user action goes in `features/`, a new page-level composition goes
   in `widgets/`. See [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) for the
   full layer breakdown.
2. Use the standard segments: `ui/`, `api/`, `model/`, `lib/` — only create
   the ones you need.
3. Export the slice's public surface from its own `index.ts`. Everything
   else is private to the slice; don't deep-import into it from outside.
4. Import from other layers through their `index.ts` too — never reach into
   `some-slice/api/some-file.ts` from outside that slice.

Run `pnpm lint:fsd` before opening a PR — it catches layer-direction
violations and public-API sidesteps that `eslint` won't.

## Before opening a PR

```bash
pnpm typecheck
pnpm lint
pnpm lint:fsd
pnpm test
pnpm build
```

`pnpm test:e2e` is not part of the fast loop (it builds and boots the app),
but CI runs it on every PR — run it locally if you touched the auth flow or
the todo board.

## Commits

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
(`feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`) and are checked by
commitlint on commit. A pre-commit hook runs `lint-staged` (ESLint + Prettier
on staged files only).

## Code-quality bar

When judging "is this good code", default to the four questions from
[Frontend Fundamentals](https://frontend-fundamentals.com/code-quality/en/):

- **Readability** — can someone read this top to bottom without holding
  five other files in their head?
- **Predictability** — does the name/signature tell you everything about
  what it does, with no hidden side effects?
- **Cohesion** — do the pieces that change together live in the same
  slice/segment?
- **Coupling** — could you delete or rewrite this slice without cascading
  changes through unrelated ones?

If you're unsure whether a piece of code is good, that guide's Discussions
are a good place to look for precedent.
