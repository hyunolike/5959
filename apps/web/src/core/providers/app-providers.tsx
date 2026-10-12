import type { ReactNode } from "react";
import { InstallAppProvider } from "@/features/install-app";
import { InteractionProvider } from "@/shared/ui";

import { QueryProvider } from "./query-provider";

/**
 * Every provider the app needs, composed in one place. Route segments under
 * `src/app` should stay thin and never register providers directly.
 */
export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <InteractionProvider>
      <InstallAppProvider>
        <QueryProvider>{children}</QueryProvider>
      </InstallAppProvider>
    </InteractionProvider>
  );
}
