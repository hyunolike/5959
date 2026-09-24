@AGENTS.md

# Working in this repo

This is a Next.js + Feature-Sliced Design (FSD) template. Before writing
code, skim [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) — it explains
the layer order (`app → core → widgets → features → entities → shared`),
why `core/` exists instead of FSD's usual `app/` layer, and the server-state
(TanStack Query) vs. client-state (Zustand) split.

Rules that are enforced by tooling, not just convention — running
`pnpm lint:fsd` (steiger) and `pnpm lint` will catch violations of these:

- Import a slice/segment only through its `index.ts`. Never deep-import a
  file inside another slice.
- Imports only point downward through the layer order above. A `features/*`
  slice cannot import from `widgets/*`, an `entities/*` slice cannot import
  from `features/*`, etc.
- Slices in the same layer do not import each other. If two features need
  to be combined, do that composition one layer up, in a widget.
- `shared/` has zero knowledge of any domain concept (no "todo", "user",
  "session" in there).

Before considering a change done, run:

```bash
pnpm typecheck && pnpm lint && pnpm lint:fsd && pnpm test && pnpm build
```
