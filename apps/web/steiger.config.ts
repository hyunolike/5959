import { defineConfig } from "steiger";
import fsd from "@feature-sliced/steiger-plugin";

/**
 * Feature-Sliced Design architecture linter (https://github.com/feature-sliced/steiger).
 * Catches things ESLint can't: wrong-direction imports between layers,
 * deep-imports that bypass a slice's public API (`index.ts`), and slices
 * with no segments.
 *
 * Two adjustments for this project's layout:
 * - Next.js owns `src/app` for routing, so FSD's own "app" layer (global
 *   providers/init) lives in `src/core` instead — mapped below.
 * - `src/shared` has no slices, only segments, so `public-api` doesn't apply.
 */
export default defineConfig([
  ...fsd.configs.recommended,
  {
    files: ["./src/shared/**"],
    rules: {
      "fsd/public-api": "off",
      "fsd/no-segmentless-slices": "off",
    },
  },
]);
