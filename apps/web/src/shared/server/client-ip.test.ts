// @vitest-environment node
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { resolveClientIp } from "./client-ip";

function requestWith(headers: Record<string, string>): NextRequest {
  return new NextRequest("http://localhost:3000/api/members/me", {
    headers,
  });
}

describe("resolveClientIp", () => {
  it("x-forwarded-for의 첫 값을 돌려준다", () => {
    const request = requestWith({
      "x-forwarded-for": "203.0.113.1, 70.41.3.18, 150.172.238.178",
    });

    expect(resolveClientIp(request)).toBe("203.0.113.1");
  });

  it("x-forwarded-for의 값 앞뒤 공백을 뺀다", () => {
    const request = requestWith({
      "x-forwarded-for": "  203.0.113.1 , 70.41.3.18",
    });

    expect(resolveClientIp(request)).toBe("203.0.113.1");
  });

  it("x-forwarded-for가 없으면 x-real-ip를 쓴다", () => {
    const request = requestWith({ "x-real-ip": "198.51.100.7" });

    expect(resolveClientIp(request)).toBe("198.51.100.7");
  });

  it("둘 다 없으면 unknown을 돌려준다", () => {
    const request = requestWith({});

    expect(resolveClientIp(request)).toBe("unknown");
  });

  it("x-forwarded-for가 IP 형식이 아니면 unknown을 돌려준다", () => {
    const request = requestWith({ "x-forwarded-for": "not-an-ip" });

    expect(resolveClientIp(request)).toBe("unknown");
  });

  it("x-forwarded-for가 IP 형식이 아니어도 x-real-ip가 유효하면 그걸 쓴다", () => {
    const request = requestWith({
      "x-forwarded-for": "not-an-ip",
      "x-real-ip": "198.51.100.7",
    });

    expect(resolveClientIp(request)).toBe("198.51.100.7");
  });

  it("x-real-ip가 IP 형식이 아니면 unknown을 돌려준다", () => {
    const request = requestWith({ "x-real-ip": "'; DROP TABLE members;--" });

    expect(resolveClientIp(request)).toBe("unknown");
  });

  it("IPv6 주소도 그대로 쓴다", () => {
    const request = requestWith({ "x-forwarded-for": "2001:db8::1" });

    expect(resolveClientIp(request)).toBe("2001:db8::1");
  });
});
