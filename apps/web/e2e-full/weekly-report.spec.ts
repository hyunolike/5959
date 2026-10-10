import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";
import {
  lastWeekStart,
  moveToLastWeek,
  waitForAnalysis,
} from "./support/report";

// infra/compose.e2e.yaml로 띄운 실제 API와 DB를 상대로 주간 리포트(008)를 확인한다.
// 지난주의 글은 화면으로 만들 수 없어, 글을 쓴 뒤 DB에서 때를 한 주 앞으로 옮긴다.
// e2e 프로필의 API는 발행 시각을 기다리지 않고 5초마다 지난주 리포트를 채운다.
// 가짜 편지 쓰기는 수치로 결과가 정해진다: 쓴 글이 7개면 두 번 실패한 뒤 성공한다.

const APP_ORIGIN = "http://localhost:3000";
const PASSWORD = "abcd1234";
const CRISIS_TEXT = "요즘 너무 힘들어서 죽고 싶어요";

interface Member {
  context: BrowserContext;
  page: Page;
}

function unique(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/** 새 컨텍스트에서 가입과 온보딩을 마친 회원. */
async function newMember(browser: Browser, prefix: string): Promise<Member> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(`wr-${prefix}-${unique()}@example.com`);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page
    .getByLabel("닉네임")
    .fill(`${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10));
  await page.getByLabel("직군").selectOption("DESIGN");
  await page.getByLabel("경력").selectOption("YEAR_3");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
  return { context, page };
}

async function post(member: Member, path: string, data?: unknown) {
  return member.page.request.fetch(path, {
    method: "POST",
    headers: { Origin: APP_ORIGIN },
    data,
  });
}

/** 화면을 거치지 않고 글을 쓴다. */
async function writePost(author: Member, content: string): Promise<number> {
  const response = await post(author, "/api/posts", {
    content,
    commentTone: "COMFORT_ME",
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { data: { postId: number } }).data.postId;
}

/** 지난주에 [contents]를 쓴 회원으로 만든다. 감정 머리말이 있는 글의 분석이 끝난 뒤에 옮긴다. */
async function writeLastWeek(author: Member, contents: string[]) {
  const postIds: number[] = [];
  for (const content of contents) {
    postIds.push(await writePost(author, content));
  }
  const analyzed = contents.filter((content) => /^\[[^\]]+:/.test(content));
  await waitForAnalysis(postIds, analyzed.length);
  moveToLastWeek(postIds);
  return postIds;
}

function reportArticle(page: Page): Locator {
  return page.getByRole("article", { name: "주간 리포트" });
}

async function openReport(member: Member, weekStart: string) {
  // 리포트는 5초마다 채워진다. 생길 때까지 다시 연다
  await expect(async () => {
    await member.page.goto(`/report/${weekStart}`);
    await expect(reportArticle(member.page)).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

async function closeAll(...members: Member[]) {
  await Promise.all(members.map((member) => member.context.close()));
}

test("US1-AC1 지난주에 글을 쓴 회원은 알림을 받고, 눌러서 지난주의 수치와 편지를 본다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const other = await newMember(browser, "o");
  const weekStart = lastWeekStart();
  const first = await writePost(
    author,
    `[불안:낮음] 발표가 걱정돼요 ${unique()}`,
  );
  expect((await post(other, `/api/posts/${first}/likes`)).status()).toBe(200);
  expect(
    (
      await post(other, `/api/posts/${first}/comments`, {
        content: "저도 그래요",
      })
    ).status(),
  ).toBe(201);
  const second = await writePost(
    author,
    `[불안:낮음] 잠이 안 와요 ${unique()}`,
  );
  const third = await writePost(
    author,
    `[짜증:낮음] 회의가 길어요 ${unique()}`,
  );
  await waitForAnalysis([first, second, third], 3);
  moveToLastWeek([first, second, third]);

  // US1-AC1: 알림이 온다
  await expect(async () => {
    await author.page.goto("/notifications");
    await expect(
      author.page.getByText("지난주 리포트가 도착했어요"),
    ).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });

  // US1-AC2: 누르면 그 주의 리포트로 간다
  await author.page.getByText("지난주 리포트가 도착했어요").click();
  await author.page.waitForURL(`**/report/${weekStart}`);

  // US1-AC3: 수치
  const article = reportArticle(author.page);
  await expect(article).toContainText("가장 많았던 감정 불안");
  const emotions = article
    .getByRole("list", { name: "감정별 글 수" })
    .getByRole("listitem");
  await expect(emotions).toHaveText([
    "불안2개",
    "무기력0개",
    "외로움0개",
    "자기비하0개",
    "짜증1개",
  ]);
  const numbers = article.getByLabel("지난주의 수치");
  await expect(numbers).toContainText("쓴 글3");
  await expect(numbers).toContainText("받은 공감1");
  await expect(numbers).toContainText("받은 댓글1");
  // US2-AC1: 편지
  await expect(article.getByRole("region", { name: "편지" })).toContainText(
    "지난주에 글을 3개 쓰셨어요",
    { timeout: 30_000 },
  );

  await closeAll(author, other);
});

test("US1-AC4 지난주에 글을 쓰지 않은 회원은 리포트도 알림도 없다", async ({
  browser,
}) => {
  const writer = await newMember(browser, "w");
  const silent = await newMember(browser, "s");
  const weekStart = lastWeekStart();
  // 이번 주에만 쓴 회원은 대상이 아니다
  await writePost(silent, `[불안:낮음] 이번 주의 글 ${unique()}`);
  await writeLastWeek(writer, [`[불안:낮음] 지난주의 글 ${unique()}`]);
  // 리포트 만들기가 한 번 돌았다는 것을 다른 회원의 리포트로 안다
  await openReport(writer, weekStart);

  await silent.page.goto(`/report/${weekStart}`);
  await expect(silent.page.getByText("이 주의 리포트가 없어요.")).toBeVisible();
  await silent.page.goto("/my?tab=reports");
  await expect(
    silent.page.getByText("아직 받은 리포트가 없어요."),
  ).toBeVisible();
  await silent.page.goto("/notifications");
  await expect(
    silent.page.getByRole("heading", { name: "알림" }),
  ).toBeVisible();
  await expect(silent.page.getByText("지난주 리포트가 도착했어요")).toHaveCount(
    0,
  );

  await closeAll(writer, silent);
});

test("US1-AC9 다른 회원의 리포트는 볼 수 없고 로그인하지 않으면 로그인 화면으로 간다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const other = await newMember(browser, "o");
  const weekStart = lastWeekStart();
  await writeLastWeek(author, [`[외로움:낮음] 혼자 점심을 먹어요 ${unique()}`]);
  await openReport(author, weekStart);

  // 주소에 회원이 없다. 같은 주소를 열어도 자기 리포트를 찾는다
  await other.page.goto(`/report/${weekStart}`);
  await expect(other.page.getByText("이 주의 리포트가 없어요.")).toBeVisible();
  const anonymous = await browser.newContext();
  const page = await anonymous.newPage();
  await page.goto(`/report/${weekStart}`);
  await page.waitForURL(/\/login/);

  await anonymous.close();
  await closeAll(author, other);
});

test("US2-AC3 편지가 늦게 써져도 화면을 열어 둔 채로 나타나고 수치는 처음부터 보인다", async ({
  browser,
}) => {
  // 가짜 편지 쓰기가 두 번 실패한다. 다시 시도는 30초, 60초 뒤다
  test.setTimeout(180_000);
  const author = await newMember(browser, "a");
  const weekStart = lastWeekStart();
  await writeLastWeek(
    author,
    Array.from(
      { length: 7 },
      (_, index) => `[무기력:낮음] 늦은 편지 ${index} ${unique()}`,
    ),
  );
  await openReport(author, weekStart);
  const article = reportArticle(author.page);

  // US2-AC2: 편지가 아직이어도 리포트는 보인다
  await expect(article.getByText("편지를 쓰고 있어요.")).toBeVisible();
  await expect(article.getByLabel("지난주의 수치")).toContainText("쓴 글7");
  let reloaded = false;
  author.page.on("load", () => {
    reloaded = true;
  });

  await expect(article.getByRole("region", { name: "편지" })).toContainText(
    "지난주에 글을 7개 쓰셨어요",
    { timeout: 150_000 },
  );
  await expect(article.getByText("편지를 쓰고 있어요.")).toHaveCount(0);
  expect(reloaded).toBe(false);

  await closeAll(author);
});

test("US2-AC6 위기 표현이 든 글이 있던 주는 편지 대신 도움받을 곳을 보인다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const weekStart = lastWeekStart();
  await writeLastWeek(author, [
    `[불안:낮음] 평범한 글 ${unique()}`,
    `[불안:낮음] ${CRISIS_TEXT} ${unique()}`,
  ]);

  await openReport(author, weekStart);

  const article = reportArticle(author.page);
  const notice = article.getByRole("region", { name: "도움 안내" });
  await expect(notice).toContainText("혼자 견디지 않아도 돼요");
  await expect(
    notice.getByRole("list", { name: "도움받을 수 있는 곳" }).getByRole("link"),
  ).toHaveText(["109", "1577-0199", "1388"]);
  await expect(article.getByRole("region", { name: "편지" })).toHaveCount(0);
  // 숨겨진 내 글도 내 한 주에 든다
  await expect(article.getByLabel("지난주의 수치")).toContainText("쓴 글2");

  await closeAll(author);
});

test("US4-AC1 마이페이지의 주간 리포트 탭에서 지난 리포트를 골라 다시 본다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const weekStart = lastWeekStart();
  await writeLastWeek(author, [`[짜증:낮음] 야근이 길어요 ${unique()}`]);
  await openReport(author, weekStart);

  await author.page.goto("/my");
  await author.page.getByRole("tab", { name: "주간 리포트" }).click();
  await author.page.waitForURL("**/my?tab=reports");
  const item = author.page
    .getByRole("tabpanel", { name: "주간 리포트" })
    .getByRole("link")
    .first();
  await expect(item).toContainText("가장 많았던 감정 짜증");
  await expect(item).toContainText("글 1개");

  // US4-AC2
  await item.click();
  await author.page.waitForURL(`**/report/${weekStart}`);
  await expect(reportArticle(author.page)).toBeVisible();

  await closeAll(author);
});
