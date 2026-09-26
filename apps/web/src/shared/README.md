# Shared

Code with zero knowledge of any business domain. If a file needs to know
about a specific feature or page, it does not belong here.

- `ui/` — generic design-system primitives (Button, Input, Card, …)
- `api/` — `fetchApiHealth`, the TanStack Query client factory
  (`createQueryClient`), and the `ApiError` type
- `config/` — typed env vars (`@t3-oss/env-nextjs`, including the
  server-only `API_ORIGIN`) and app-wide constants
- `lib/` — framework-agnostic helpers (`cn`, `formatDate`, generic hooks
  like `useDebouncedValue`)

Every segment re-exports its public surface through its own `index.ts` —
import `@/shared/api`, not `@/shared/api/api-health`. `steiger` enforces this.

`shared` cannot import from any other layer. Everything else may import from
`shared`.
