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
  await expect(page.getByRole("region", { name: "최근 이야기" })).toBeVisible();
  await expect(page.getByRole("region", { name: "피드" })).toHaveCount(0);
});

test("011 US1-AC1/3 US2-AC5 실 API는 로그인 없이 최근 글을 닉네임 없이 주고 상세는 거부한다", async ({
  request,
}) => {
  const response = await request.get("/api/public/feed");
  expect(response.status()).toBe(200);
  const raw = await response.text();
  const body = JSON.parse(raw);
  expect(body.success).toBe(true);
  expect(body.data.items.length).toBeLessThanOrEqual(20);
  expect(body.data).not.toHaveProperty("nextCursor");
  expect(raw).not.toMatch(/"(nickname|author|authorId|memberId)"/);
  for (const item of body.data.items) {
    const detail = await request.get(`/api/posts/${item.postId}`);
    expect(detail.status()).toBe(401);
  }
});

test("011 US1-AC4 실 API의 글을 누르면 로그인 안내가 열리고 상세 주소는 로그인으로 간다", async ({
  page,
}) => {
  await page.goto("/home");
  const stories = page.getByRole("region", { name: "최근 이야기" });
  await expect(stories.getByRole("alert")).toHaveCount(0);
  const open = stories.getByRole("button", { name: "로그인하고 이 글 읽기" });
  if ((await open.count()) === 0) {
    await expect(
      stories.getByText("아직 올라온 이야기가 없어요."),
    ).toBeVisible();
    return;
  }
  await open.first().click();
  await page
    .getByRole("dialog", { name: "로그인이 필요해요" })
    .getByRole("button", { name: "로그인하기" })
    .click();
  await expect(page).toHaveURL(/\/login\?next=%2Fpost%2F\d+$/);
});
