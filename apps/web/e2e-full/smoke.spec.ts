import { expect, test } from "@playwright/test";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. /api/health를 목(mock)하지 않는다.
test("실제 API와 연결된 E2E 환경에서 첫 화면이 서버 정상을 보여준다", async ({
  page,
}) => {
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "오구오구" })).toBeVisible();
  await expect(page.getByRole("status")).toHaveText("서버 정상");
});
