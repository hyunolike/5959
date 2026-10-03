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
  // TEMPORARY (003-core-loop T011/T014): post, monster, comment entities were
  // added with only model/types.ts and have no consumer yet, so steiger's
  // insignificant-slice rule flags all three as "no references". Later
  // batches (T020/T027/T028/T034/T045/T053) wire features/widgets up to
  // these entities, which gives steiger real references. Remove this
  // override at T053 (specs/003-core-loop/tasks.md) once all three slices
  // have at least one consumer.
  {
    files: [
      "./src/entities/post/**",
      "./src/entities/monster/**",
      "./src/entities/comment/**",
    ],
    rules: {
      "fsd/insignificant-slice": "off",
    },
  },
  // features/like and features/comment are separate slices on purpose: plan.md
  // (003-core-loop, Constitution Check I and Source Code) names them as two user
  // actions, and features/comment grows edit/delete in US4 (T049). For now only
  // widgets/post-detail composes them, which insignificant-slice reads as "merge
  // into the widget". Merging would put the HP rules and mutations inside a
  // widget and break the one-action-per-feature layout.
  {
    files: ["./src/features/like/**", "./src/features/comment/**"],
    rules: {
      "fsd/insignificant-slice": "off",
    },
  },
]);
