# Shared

Code with zero knowledge of any business domain. If a file mentions "todo" or
"user" or "session", it does not belong here.

- `ui/` — generic design-system primitives (Button, Input, Card, …)
- `api/` — the axios instance, its refresh-token interceptor, the TanStack
  Query client factory, and the `ApiError` type
- `config/` — typed env vars (`@t3-oss/env-nextjs`) and app-wide constants
- `lib/` — framework-agnostic helpers (`cn`, `formatDate`, `tokenStorage`,
  generic hooks like `useDebouncedValue`)
- `server/` — mock-backend-only helpers used by `src/app/api/**` route
  handlers (fake JWT signing, in-memory data). Never imported from client code.

Every segment re-exports its public surface through its own `index.ts` —
import `@/shared/api`, not `@/shared/api/http-client`. `steiger` enforces this.

`shared` cannot import from any other layer. Everything else may import from
`shared`.
