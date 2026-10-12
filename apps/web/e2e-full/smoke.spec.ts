import { expect, test } from "@playwright/test";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. /api/health를 목(mock)하지 않는다.
test("US1-AC1 실제 API가 정상이고 첫 접속은 로그인 화면으로 안내한다", async ({
  page,
}) => {
  await page.goto("/");

  await expect(page).toHaveURL(/\/login$/);
  await expect(
    page.getByRole("heading", { name: "로그인", level: 1 }),
  ).toBeVisible();
  const health = await page.request.get("/api/health");
  expect(health.ok()).toBe(true);
  expect(await health.json()).toEqual({ status: "UP" });
});
