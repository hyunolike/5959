import { expect, test } from "@playwright/test";

test("api/health/route.ts가 캐치올 프록시(api/[...path])보다 먼저 매칭된다", async ({
  request,
}) => {
  const response = await request.get("/api/health");

  expect(response.status()).toBe(200);
  const body = await response.json();
  // /api/[...path]로 잡혔다면 ApiResponse 봉투({ success, data, error })이거나
  // API_ORIGIN에 연결하지 못해 502가 됐을 것이다. /api/health만의 모양을 확인한다.
  expect(body).toEqual({ status: expect.stringMatching(/^(UP|DOWN)$/) });
  expect(body).not.toHaveProperty("success");
});
