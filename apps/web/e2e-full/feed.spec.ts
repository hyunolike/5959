import { expect, test, type Page } from "@playwright/test";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. 다른 스펙이 동시에 글을
// 쓰므로(fullyParallel), 단언은 이 스펙이 만든 글에 대해서만 하거나 이 스펙만 쓰는
// 직군과 경력 조합으로 거른 피드에서 한다. 글은 바로 분석되는 머리말(`[감정:강도]`)로 쓴다.
// `[실패]` 글은 24시간 동안 재시도 대기열에 남아 다른 스펙(write-post US1-AC3)의 재시도를 늦춘다.

/**
 * 홈의 두 조회(내 프로필, 피드)가 끝날 때까지 기다린다. 끝나기 전에 쿠키를 지우면 늦게 나간
 * 조회가 401을 받아 로그인 화면으로 튕기고, 다음 page.goto가 ERR_ABORTED로 끊긴다.
 */
async function waitForHomeLoaded(page: Page) {
  await expect(page.getByRole("heading")).toContainText("님, 반가워요");
  const feed = page.getByRole("region", { name: "피드" });
  await expect(
    feed.getByRole("list").or(feed.getByText(/고민이 없어요/)),
  ).toBeVisible();
}

const APP_ORIGIN = "http://localhost:3000";

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

/** 세션을 끊고 새 회원으로 가입과 온보딩을 마친다. */
async function onboardNewMember(
  page: Page,
  jobRole: string,
  careerYear: string,
): Promise<string> {
  await page.context().clearCookies();
  const nickname = uniqueNickname("f");
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(uniqueEmail("feed"));
  await page.getByLabel("비밀번호").fill("abcd1234");
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption(jobRole);
  await page.getByLabel("경력").selectOption(careerYear);
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
  return nickname;
}

/** 지금 로그인한 회원으로 글을 쓴다(BFF 프록시, 같은 출처). 글 ID를 돌려준다. */
async function createPost(page: Page, content: string): Promise<number> {
  const response = await page.request.post("/api/posts", {
    headers: { Origin: APP_ORIGIN },
    data: { content, commentTone: "COMFORT_ME" },
  });
  expect(response.status()).toBe(201);
  const body = (await response.json()) as { data: { postId: number } };
  return body.data.postId;
}

/** 피드 카드의 글 ID를 화면 순서대로 읽는다. */
async function feedPostIds(page: Page): Promise<number[]> {
  const hrefs = await page
    .getByRole("region", { name: "피드" })
    .getByRole("listitem")
    .getByRole("link")
    .evaluateAll((links) => links.map((link) => link.getAttribute("href")));
  return hrefs.map((href) => Number(href?.split("/").pop()));
}

function card(page: Page, postId: number) {
  return page.locator(`a[href="/post/${postId}"]`);
}

test("US2-AC1 최신 글부터 작성자, 직군과 경력, 본문 앞부분, 감정과 몬스터, 공감 수와 댓글 수가 보인다", async ({
  page,
}) => {
  const nickname = await onboardNewMember(page, "PLANNING", "YEAR_2");
  const content = `[불안:낮음] 피드에 보일 고민 ${Date.now()} ${"가".repeat(60)}`;
  const postId = await createPost(page, content);

  // 감정 분석이 끝나 몬스터가 생길 때까지 기다린 뒤 피드를 연다
  await page.goto(`/post/${postId}`);
  await expect(page.getByText("HP 10/10")).toBeVisible({ timeout: 30_000 });

  await page.goto("/home");
  const item = card(page, postId);
  await expect(item).toBeVisible();
  await expect(item.getByText(nickname, { exact: true })).toBeVisible();
  await expect(item.getByText("기획 · 2년차")).toBeVisible();
  // 미리보기는 앞 50글자에 "..."를 붙인다
  await expect(
    item.getByText(`${[...content].slice(0, 50).join("")}...`),
  ).toBeVisible();
  await expect(item.getByText("불안", { exact: true })).toBeVisible();
  await expect(item.getByText("HP 10/10")).toBeVisible();
  await expect(item.getByText("공감 0")).toBeVisible();
  await expect(item.getByText("댓글 0")).toBeVisible();

  // 방금 쓴 글이 다른 글보다 앞에 온다(최신순)
  const ids = await feedPostIds(page);
  expect(ids.indexOf(postId)).toBeGreaterThanOrEqual(0);
  expect(ids.slice(0, ids.indexOf(postId)).every((id) => id > postId)).toBe(
    true,
  );
});

test("US2-AC2 피드 끝까지 내리면 다음 20개가 이어서 붙고 같은 글이 두 번 나오지 않는다", async ({
  page,
}) => {
  test.setTimeout(120_000);
  // 이 테스트만 쓰는 직군과 경력 조합. 회원마다 1시간에 10개까지라 셋이 8개씩 쓴다.
  // 다시 돌리면(재시도, --repeat-each) 같은 조합에 다른 실행의 글이 섞일 수 있으므로
  // 첫 쪽이 정확히 내 글 20개라고 보지 않고, 내 글이 모두 최신순으로 한 번씩 나오는지 본다.
  const mine: number[] = [];
  for (let member = 0; member < 3; member += 1) {
    await onboardNewMember(page, "ACCOUNTING", "YEAR_6");
    for (let i = 0; i < 8; i += 1) {
      mine.push(
        await createPost(page, `[무기력:낮음] 이어 보기 ${member}-${i}`),
      );
    }
  }

  await page.goto("/home?jobRole=ACCOUNTING&careerYear=YEAR_6");
  const items = page
    .getByRole("region", { name: "피드" })
    .getByRole("listitem");
  await expect(items).toHaveCount(20);

  await items.last().scrollIntoViewIfNeeded();
  await page.mouse.wheel(0, 10_000);
  // 다음 쪽 요청은 BFF가 세션을 갱신하느라 늦을 수 있다(e2e의 access 토큰 수명은 5초)
  await expect
    .poll(() => items.count(), { timeout: 15_000 })
    .toBeGreaterThan(20);

  const ids = await feedPostIds(page);
  expect(new Set(ids).size).toBe(ids.length);
  expect([...ids].sort((a, b) => b - a)).toEqual(ids);
  expect(ids).toEqual(expect.arrayContaining(mine));
});

test("US2-AC3 인기순을 고르면 공감 수가 많은 글부터, 같으면 최신 글부터 나온다", async ({
  page,
}) => {
  await onboardNewMember(page, "SALES", "YEAR_5");
  const older = await createPost(page, "[외로움:낮음] 인기순 확인 1");
  const newer = await createPost(page, "[외로움:낮음] 인기순 확인 2");

  await page.goto("/home");
  await page.getByRole("button", { name: "인기순" }).click();
  await page.waitForURL(/\/home\?order=POPULAR$/);
  await expect(page.getByRole("button", { name: "인기순" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );

  const feed = page.getByRole("region", { name: "피드" });
  await expect(feed.getByRole("listitem").first()).toBeVisible();
  const rows = await feed.getByRole("listitem").evaluateAll((elements) =>
    elements.map((element) => ({
      id: Number(
        element.querySelector("a")?.getAttribute("href")?.split("/").pop(),
      ),
      likes: Number(/공감 (\d+)/.exec(element.textContent ?? "")?.[1]),
    })),
  );
  for (let i = 1; i < rows.length; i += 1) {
    const [before, after] = [rows[i - 1], rows[i]];
    expect(
      before.likes > after.likes ||
        (before.likes === after.likes && before.id > after.id),
    ).toBe(true);
  }

  // 공감 0인 내 글 둘은 같은 공감 수 안에서 최신 글이 먼저다(같은 조합으로 거른다)
  await page.goto("/home?order=POPULAR&jobRole=SALES&careerYear=YEAR_5");
  await expect(card(page, newer)).toBeVisible();
  await expect(card(page, older)).toBeVisible();
  const ids = await feedPostIds(page);
  expect(ids.indexOf(newer)).toBeLessThan(ids.indexOf(older));
});

test("US2-AC4 직군과 경력을 고르면 고른 직군 중 하나이면서 고른 경력 중 하나인 작성자의 글만 나온다", async ({
  page,
}) => {
  const stamp = Date.now();
  await onboardNewMember(page, "HR", "YEAR_4");
  const hrYear4 = await createPost(page, `[짜증:낮음] 인사 4년차 ${stamp}`);
  await onboardNewMember(page, "PRODUCTION", "YEAR_4");
  const productionYear4 = await createPost(
    page,
    `[짜증:낮음] 생산 4년차 ${stamp}`,
  );
  await onboardNewMember(page, "HR", "NEWCOMER");
  const hrNewcomer = await createPost(page, `[짜증:낮음] 인사 신입 ${stamp}`);

  await page.goto("/home");
  await page.locator("summary", { hasText: "필터" }).click();
  const jobs = page.getByRole("group", { name: "직군" });
  const careers = page.getByRole("group", { name: "경력" });
  await jobs.getByRole("button", { name: "인사" }).click();
  await page.waitForURL(/jobRole=HR/);
  await jobs.getByRole("button", { name: "생산" }).click();
  await page.waitForURL(/jobRole=PRODUCTION/);
  await careers.getByRole("button", { name: "4년차" }).click();
  await page.waitForURL(/careerYear=YEAR_4/);

  await expect(card(page, hrYear4)).toBeVisible();
  await expect(card(page, productionYear4)).toBeVisible();
  await expect(card(page, hrNewcomer)).toHaveCount(0);
  const metas = await page
    .getByRole("region", { name: "피드" })
    .getByRole("listitem")
    .evaluateAll((elements) =>
      elements.map((element) => element.textContent ?? ""),
    );
  for (const text of metas) {
    expect(text).toMatch(/(인사|생산) · 4년차/);
  }
});
