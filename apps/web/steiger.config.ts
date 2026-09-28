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
  {
    // 002-auth의 태스크 순서상 entities/member(T024)가 이를 쓰는
    // features/onboarding(T038), app/home·app/my(T040)보다 먼저 배치로 들어온다.
    // 그 전까지는 아무도 참조하지 않아 insignificant-slice가 걸린다.
    // T038, T040이 끝나면 이 예외는 지운다.
    files: ["./src/entities/member/**"],
    rules: {
      "fsd/insignificant-slice": "off",
    },
  },
]);
