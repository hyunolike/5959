// @vitest-environment node
import { NextRequest } from "next/server";
import { describe, expect, it, vi } from "vitest";

import { guardOrigin } from "./origin-guard";

vi.mock("@/shared/config", () => ({
  env: { APP_ORIGIN: "http://localhost:3000" },
}));

function requestWith(method: string, origin?: string): NextRequest {
  const headers: Record<string, string> = {};
  if (origin !== undefined) {
    headers.origin = origin;
  }
  return new NextRequest("http://localhost:3000/api/auth/login", {
    method,
    headers,
  });
}

describe("guardOrigin", () => {
  it("Origin이 APP_ORIGIN과 같으면 POST를 통과시킨다", async () => {
    const result = guardOrigin(requestWith("POST", "http://localhost:3000"));
    expect(result).toBeNull();
  });

  it("Origin이 APP_ORIGIN과 다르면 POST를 403 FORBIDDEN_ORIGIN으로 막는다", async () => {
    const result = guardOrigin(requestWith("POST", "http://evil.example"));
    expect(result).not.toBeNull();
    expect(result?.status).toBe(403);
    const body = await result?.json();
    expect(body).toEqual({
      success: false,
      data: null,
      error: {
        code: "FORBIDDEN_ORIGIN",
        message: expect.any(String),
      },
    });
  });

  it("Origin이 없으면 POST를 403 FORBIDDEN_ORIGIN으로 막는다", async () => {
    const result = guardOrigin(requestWith("POST"));
    expect(result?.status).toBe(403);
  });

  it.each(["PUT", "PATCH", "DELETE"])(
    "%s도 Origin이 다르면 막는다",
    (method) => {
      const result = guardOrigin(requestWith(method, "http://evil.example"));
      expect(result?.status).toBe(403);
    },
  );

  it.each(["GET", "HEAD"])("%s는 Origin과 관계없이 통과시킨다", (method) => {
    const result = guardOrigin(requestWith(method, "http://evil.example"));
    expect(result).toBeNull();
  });
});
