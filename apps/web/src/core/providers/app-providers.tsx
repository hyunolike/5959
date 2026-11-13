import type { ReactNode } from "react";
import { InstallAppProvider } from "@/features/install-app";
import { InteractionProvider } from "@/shared/ui";

import { QueryProvider } from "./query-provider";
import { AuthenticatedApiBoundary } from "./authenticated-api-boundary";

/**
 * Every provider the app needs, composed in one place. Route segments under
 * `src/app` should stay thin and never register providers directly.
 */
export function AppProviders({
  children,
  authenticated = false,
}: {
  children: ReactNode;
  authenticated?: boolean;
}) {
  return (
    <InteractionProvider>
      <InstallAppProvider>
        <QueryProvider>
          <AuthenticatedApiBoundary authenticated={authenticated}>
            {children}
          </AuthenticatedApiBoundary>
        </QueryProvider>
      </InstallAppProvider>
    </InteractionProvider>
  );
}
