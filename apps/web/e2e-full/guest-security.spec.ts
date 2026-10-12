import { expect, test } from "@playwright/test";

test("GUEST-US1-AC4 실 API는 비로그인 피드·상세·개인정보·글쓰기·공감·댓글을 거부한다", async ({
  request,
  baseURL,
}) => {
  for (const path of ["/api/feed", "/api/posts/1", "/api/members/me"]) {
    const response = await request.get(path);
    expect(response.status()).toBe(401);
    expect((await response.json()).success).toBe(false);
  }
  for (const [path, data] of [
    ["/api/posts", { content: "미인증 거부 검증", commentTone: "WARM" }],
    ["/api/posts/1/likes", {}],
    ["/api/posts/1/comments", { content: "미인증 거부 검증" }],
  ] as const) {
    const response = await request.post(path, {
      headers: { origin: baseURL! },
      data,
    });
    expect(response.status()).toBe(401);
    expect((await response.json()).success).toBe(false);
  }
});

test("GUEST-US1-AC4 만료된 세션은 기존 갱신/로그인 흐름을 유지하고 다시 둘러보면 개인 데이터가 없다", async ({
  page,
  context,
  baseURL,
}) => {
  await context.addCookies([
    {
      name: "__Host-ogu_rt",
      value: "expired-review-session",
      url: baseURL!.replace("http:", "https:"),
      secure: true,
      sameSite: "Lax",
    },
    {
      name: "__Host-ogu_ob",
      value: "1",
      url: baseURL!.replace("http:", "https:"),
      secure: true,
      sameSite: "Lax",
    },
  ]);
  await page.goto("/home");
  await expect(page).toHaveURL(/\/login\?next=%2Fhome$/);
  expect(
    (await context.cookies()).filter((c) => c.name.startsWith("__Host-ogu_")),
  ).toEqual([]);
  await page.getByRole("link", { name: "먼저 둘러보기" }).click();
  await expect(page).toHaveURL(/\/home$/);
  await expect(
    page.getByRole("heading", { name: "마음 이야기는 로그인 후 볼 수 있어요" }),
  ).toBeVisible();
  await expect(page.getByRole("region", { name: "피드" })).toHaveCount(0);
});
