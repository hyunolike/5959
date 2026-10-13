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

test("011 US1-AC1/3/4 US2-AC5 실 API의 글이 닉네임 없이 보이고 누르면 로그인 안내가 그 글로 열린다", async ({
  page,
  browser,
  baseURL,
}) => {
  // 회원 한 명이 글을 쓴다. `e2e` 프로필의 가짜 분류기는 표지가 없는 글을 바로 위험 없음으로 닫는다.
  const nickname = `g${Math.random().toString(36).slice(2, 8)}`;
  const content = `[불안:낮음] 둘러보기 검증 ${Date.now()} 내일 발표가 걱정돼요`;
  await page.goto("/signup");
  await page
    .getByLabel("이메일")
    .fill(
      `guest-feed-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`,
    );
  await page.getByLabel("비밀번호").fill("abcd1234");
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption("DESIGN");
  await page.getByLabel("경력").selectOption("YEAR_5");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  const created = await page.request.post("/api/posts", {
    headers: { Origin: baseURL! },
    data: { content, commentTone: "COMFORT_ME" },
  });
  expect(created.status()).toBe(201);
  const postId = ((await created.json()) as { data: { postId: number } }).data
    .postId;

  // 로그인하지 않은 방문자. 위험 판정이 끝나면 목록에 나온다(US2-AC3).
  const guest = await browser.newContext();
  try {
    let raw = "";
    await expect
      .poll(
        async () => {
          const response = await guest.request.get("/api/public/feed");
          expect(response.status()).toBe(200);
          raw = await response.text();
          return (
            JSON.parse(raw) as { data: { items: { postId: number }[] } }
          ).data.items.map((item) => item.postId);
        },
        { timeout: 20_000 },
      )
      .toContain(postId);
    const data = (
      JSON.parse(raw) as {
        data: { items: Record<string, unknown>[]; nextCursor?: unknown };
      }
    ).data;
    expect(data.items.length).toBeLessThanOrEqual(20);
    expect(data).not.toHaveProperty("nextCursor");
    expect(raw).not.toContain(nickname);
    expect(raw).not.toMatch(/"(nickname|author|authorId|memberId)"/);
    for (const path of [
      `/api/posts/${postId}`,
      `/api/posts/${postId}/comments`,
    ]) {
      expect((await guest.request.get(path)).status()).toBe(401);
    }

    // 화면은 목록의 첫 글로 본다. 다른 스펙이 함께 글을 써서 이 글은 스무 개 밖으로 밀릴 수 있다.
    const guestPage = await guest.newPage();
    await guestPage.goto("/home");
    const stories = guestPage.getByRole("region", { name: "최근 이야기" });
    const first = stories.getByRole("listitem").first();
    await expect(first).toBeVisible();
    await expect(guestPage.getByText(nickname)).toHaveCount(0);
    await expect(guestPage.locator('a[href^="/post/"]')).toHaveCount(0);
    await first.getByRole("button", { name: "로그인하고 이 글 읽기" }).click();
    await guestPage
      .getByRole("dialog", { name: "로그인이 필요해요" })
      .getByRole("button", { name: "로그인하기" })
      .click();
    await expect(guestPage).toHaveURL(/\/login\?next=%2Fpost%2F\d+$/);
  } finally {
    await guest.close();
  }
});
