import { expect, test } from "@playwright/test";

test("US1-AC1 첫 접속 시 로그인 화면에서 서비스를 시작할 수 있다", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/login$/);
  await expect(
    page.getByRole("heading", { name: "로그인", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "구글로 계속하기" }),
  ).toBeVisible();
});

test("US1-AC2 로그인한 사용자는 첫 접속에서 홈으로 이동한다", async ({
  request,
}) => {
  const response = await request.get("/", {
    headers: { cookie: "__Host-ogu_rt=test-session; __Host-ogu_ob=1" },
    maxRedirects: 0,
  });
  expect(response.status()).toBe(307);
  expect(new URL(response.headers().location, response.url()).pathname).toBe(
    "/home",
  );
});
