import { expect, test } from "@playwright/test";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. /api/health를 목(mock)하지 않는다.
test("US1-AC1 실제 API가 정상이고 첫 접속은 서비스 소개를 보여 준다", async ({
  page,
}) => {
  await page.goto("/");

  // 첫 화면은 로그인하지 않은 방문자와 검색 엔진이 보는 서비스 소개다(SEO-AC1).
  await expect(page).toHaveURL(/\/$/);
  await expect(
    page.getByRole("heading", { level: 1, name: /오구오구/ }),
  ).toBeVisible();
  const health = await page.request.get("/api/health");
  expect(health.ok()).toBe(true);
  expect(await health.json()).toEqual({ status: "UP" });
});
