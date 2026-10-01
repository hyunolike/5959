// @vitest-environment node
import { describe, expect, it } from "vitest";
// Next.js가 `config.matcher`의 각 패턴을 실제 미들웨어 실행 여부를 가르는
// 정규식으로 컴파일할 때 쓰는 바로 그 함수다(next/dist/build/analysis/get-page-static-info.js
// `getMiddlewareMatchers`). 패턴 문자열만 보고 짐작하는 대신, Next.js가 실제로
// 만드는 정규식으로 검증한다.
import { tryToParsePath } from "next/dist/lib/try-to-parse-path";

import { config } from "./proxy";

/** `config.matcher`가 실제로 이 pathname에서 proxy()를 실행시키는지. */
function middlewareRuns(pathname: string): boolean {
  const [pattern] = config.matcher;
  const { regexStr } = tryToParsePath(pattern);
  if (regexStr === undefined) {
    throw new Error(`matcher 패턴을 정규식으로 바꾸지 못했다: ${pattern}`);
  }
  return new RegExp(regexStr).test(pathname);
}

describe("proxy matcher", () => {
  it("실제 /api/* 경로는 제외한다(자기만의 origin-guard가 있다)", () => {
    expect(middlewareRuns("/api/health")).toBe(false);
    expect(middlewareRuns("/api/auth/login")).toBe(false);
  });

  it("정적 자산과 파비콘은 제외한다", () => {
    expect(middlewareRuns("/_next/static/chunk.js")).toBe(false);
    expect(middlewareRuns("/_next/image")).toBe(false);
    expect(middlewareRuns("/favicon.ico")).toBe(false);
  });

  it("일반 페이지는 가드를 거친다", () => {
    expect(middlewareRuns("/home")).toBe(true);
    expect(middlewareRuns("/login")).toBe(true);
  });

  it("'api'로 시작하지만 /api/ 하위가 아닌 페이지는 가드에서 빠지면 안 된다(예: /apiary)", () => {
    expect(middlewareRuns("/apiary")).toBe(true);
    expect(middlewareRuns("/apikeys")).toBe(true);
  });
});
