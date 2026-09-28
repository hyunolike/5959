// @vitest-environment node
import { describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";

import { fetchMe } from "./queries";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

describe("fetchMe", () => {
  it("FR-015 같은 출처의 /api/members/me를 호출해 내 프로필을 돌려준다", async () => {
    const member = {
      id: 1,
      authMethod: "EMAIL",
      email: "a@a.com",
      nickname: "오구",
      jobRole: "DEVELOPMENT",
      careerYear: "YEAR_1",
      onboarded: true,
    };
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: member, error: null }),
      );

    await expect(fetchMe(fetchImpl)).resolves.toEqual(member);
    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/members/me",
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("오류 응답이면 ApiError를 던진다", async () => {
    const errorBody = {
      success: false,
      data: null,
      error: { code: "UNAUTHORIZED", message: "인증이 필요합니다." },
    };
    const fetchImpl = vi
      .fn()
      .mockImplementation(async () => jsonResponse(errorBody, 401));

    const rejection = fetchMe(fetchImpl);
    await expect(rejection).rejects.toBeInstanceOf(ApiError);
    await expect(rejection).rejects.toMatchObject({
      status: 401,
      code: "UNAUTHORIZED",
    });
  });
});
