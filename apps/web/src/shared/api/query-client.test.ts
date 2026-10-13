import { describe, expect, it, vi } from "vitest";

import { ApiError } from "./api-error";
import { ANONYMOUS_QUERY_META, createQueryClient } from "./query-client";

describe("createQueryClient", () => {
  it("조회가 401(세션 만료)로 실패하면 로그인 화면으로 보내는 콜백을 부른다", async () => {
    const onUnauthorized = vi.fn();
    const client = createQueryClient(onUnauthorized);

    await client
      .fetchQuery({
        queryKey: ["me"],
        queryFn: () => {
          throw new ApiError(401, { message: "만료", code: "SESSION_EXPIRED" });
        },
      })
      .catch(() => {});

    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it("401이 아닌 실패에는 부르지 않는다", async () => {
    const onUnauthorized = vi.fn();
    const client = createQueryClient(onUnauthorized);

    await client
      .fetchQuery({
        queryKey: ["me"],
        queryFn: () => {
          throw new ApiError(404, { message: "없음" });
        },
      })
      .catch(() => {});

    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("011 US1-AC7 로그인 없이 부르는 조회의 401은 로그인 화면으로 보내지 않는다", async () => {
    const onUnauthorized = vi.fn();
    const client = createQueryClient(onUnauthorized);

    await client
      .fetchQuery({
        queryKey: ["public-feed"],
        meta: ANONYMOUS_QUERY_META,
        queryFn: () => {
          throw new ApiError(401, { message: "인증 필요" });
        },
      })
      .catch(() => {});

    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
