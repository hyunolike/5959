import path from "node:path";

import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    tsconfigPaths: true,
    alias: {
      // Next.js는 서버 번들에서 `server-only`를 빈 모듈로 alias한다(client 번들에서만
      // throw하게 하려고). Vitest에도 같은 alias를 둬서 서버 전용 모듈을 그대로 테스트한다.
      "server-only": path.resolve(__dirname, "vitest.server-only-stub.ts"),
    },
  },
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    exclude: ["**/node_modules/**", "**/e2e/**", "**/e2e-full/**"],
  },
});
