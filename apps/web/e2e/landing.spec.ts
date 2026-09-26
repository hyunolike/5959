import { expect, test } from "@playwright/test";

test("US1-AC1 API가 정상이면 서버 정상을 보여 준다", async ({ page }) => {
  await page.route("**/api/health", (route) =>
    route.fulfill({ json: { status: "UP" } }),
  );

  await page.goto("/");

  await expect(page.getByRole("heading", { name: "오구오구" })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText("서버 정상");
});

test("US1-AC2 API가 응답하지 않으면 서버 점검 중을 보여 준다", async ({
  page,
}) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "오구오구" })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText("서버 점검 중");
});
