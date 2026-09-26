@AGENTS.md

# Working in this repo

This app is organized with Feature-Sliced Design (FSD). Before writing
code, skim [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) — it explains
the layer order (`app → core → widgets → features → entities → shared`),
why `core/` exists instead of FSD's usual `app/` layer, and the server-state
(TanStack Query) vs. client-state (Zustand) split. Only `shared` and
`widgets/service-status` exist so far; `features` and `entities` are added
as real domain work lands.

Auth follows the BFF pattern ([ADR-0002](../../docs/adr/0002-bff-auth.md)):
the browser only ever calls same-origin `/api/*` route handlers, and a
route handler forwards to the server-only `API_ORIGIN`. Never call the
backend origin directly from client code.

Rules that are enforced by tooling, not just convention — running
`pnpm lint:fsd` (steiger) and `pnpm lint` will catch violations of these:

- Import a slice/segment only through its `index.ts`. Never deep-import a
  file inside another slice.
- Imports only point downward through the layer order above. A `features/*`
  slice cannot import from `widgets/*`, an `entities/*` slice cannot import
  from `features/*`, etc.
- Slices in the same layer do not import each other. If two features need
  to be combined, do that composition one layer up, in a widget.
- `shared/` has zero knowledge of any domain concept.

Test names start with the spec's acceptance criterion ID, e.g. `US1-AC1 ...`.

Run every command from the repo root as `pnpm --filter web <script>`, e.g.:

```bash
pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web test && pnpm --filter web build
```
