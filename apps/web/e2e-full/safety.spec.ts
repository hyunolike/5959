import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";

// infra/compose.e2e.yaml로 띄운 실제 API와 DB를 상대로 위험 감지와 안전장치(005)를 확인한다.
// 작성자와 다른 회원은 서로 다른 브라우저 컨텍스트(쿠키)를 쓴다.
// 문장은 모두 검증용으로 지어낸 것이다. 위기와 우려 표현은 V5 시드의 낱말에 걸린다.

const APP_ORIGIN = "http://localhost:3000";
const PASSWORD = "abcd1234";
const CRISIS_TEXT = "요즘 너무 힘들어서 죽고 싶어요";
const CONCERN_TEXT = "이제는 다 포기하고 싶어요";

interface Member {
  context: BrowserContext;
  page: Page;
  nickname: string;
}

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

/** 새 컨텍스트에서 가입과 온보딩을 마친 회원. */
async function newMember(browser: Browser, prefix: string): Promise<Member> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const nickname = uniqueNickname(prefix);
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(uniqueEmail(`safe-${prefix}`));
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption("DESIGN");
  await page.getByLabel("경력").selectOption("YEAR_3");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
  return { context, page, nickname };
}

/** 지금 로그인한 회원으로 같은 출처 BFF를 부른다. 화면은 건드리지 않는다. */
async function call(page: Page, path: string, data?: unknown) {
  return page.request.fetch(path, {
    method: "POST",
    headers: { Origin: APP_ORIGIN },
    data,
  });
}

/** 분석이 계속 실패해 몬스터가 생기지 않는 글. 몬스터 알림이 섞이지 않게 할 때 쓴다. */
async function writePost(author: Member, content: string): Promise<number> {
  const response = await call(author.page, "/api/posts", {
    content,
    commentTone: "COMFORT_ME",
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { data: { postId: number } }).data.postId;
}

/** 글쓰기 화면에서 실제로 써서 올린다. 올리면 글 상세로 이동한다. */
async function writePostOnScreen(author: Member, content: string) {
  await author.page.goto("/write");
  await author.page.getByLabel("고민").fill(content);
  await author.page.getByRole("button", { name: "무조건 위로해주기" }).click();
  await author.page.getByRole("button", { name: "올리기" }).click();
  await author.page.waitForURL(/\/post\/\d+$/);
  return Number(new URL(author.page.url()).pathname.split("/").pop());
}

function notice(page: Page): Locator {
  return page.getByRole("region", { name: "도움 안내" });
}

function feed(page: Page): Locator {
  return page.getByRole("region", { name: "피드" });
}

async function closeAll(...members: Member[]) {
  await Promise.all(members.map((member) => member.context.close()));
}

test("US1-AC1 위기 표현이 든 글을 올리면 글 상세에서 바로 도움 리소스와 숨김 설명을 본다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const marker = `위기 글 ${Date.now()}`;

  const postId = await writePostOnScreen(author, `${CRISIS_TEXT} ${marker}`);

  const banner = notice(author.page);
  await expect(banner).toBeVisible();
  await expect(banner).toContainText("이 글은 다른 회원에게 보이지 않아요.");
  const links = banner
    .getByRole("list", { name: "도움받을 수 있는 곳" })
    .getByRole("link");
  await expect(links).toHaveText(["109", "1577-0199", "1388"]);
  await expect(links.first()).toHaveAttribute("href", "tel:109");
  // 닫을 수 없다
  await expect(banner.getByRole("button", { name: "접기" })).toHaveCount(0);
  // 내 글은 나에게 그대로 보인다
  await expect(author.page.getByText(marker)).toBeVisible();
  expect(postId).toBeGreaterThan(0);

  await closeAll(author);
});

test("US1-AC2 위기로 숨겨진 글은 다른 회원의 피드에 없고 주소로 열어도 삭제된 글 안내를 받는다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const other = await newMember(browser, "b");
  const marker = `숨길 글 ${Date.now()}`;
  const visibleMarker = `보일 글 ${Date.now()}`;
  await writePost(author, `[실패] ${visibleMarker}`);
  const hidden = await writePost(author, `[실패] ${CRISIS_TEXT} ${marker}`);

  await other.page.goto("/home");
  await waitForHomeLoaded(other.page);

  await expect(feed(other.page)).toContainText(visibleMarker);
  await expect(feed(other.page)).not.toContainText(marker);

  await other.page.goto(`/post/${hidden}`);

  await expect(other.page.getByText("삭제된 글이에요.")).toBeVisible();
  await expect(other.page.getByRole("main")).not.toContainText(marker);

  await closeAll(author, other);
});

test("US1-AC3 숨겨진 내 글은 내 글 목록에 숨김 표시와 함께 보이고 열면 설명과 도움 리소스가 있다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const marker = `내 숨긴 글 ${Date.now()}`;
  const postId = await writePost(author, `[실패] ${CRISIS_TEXT} ${marker}`);

  await author.page.goto("/my");

  const mine = author.page
    .getByRole("list", { name: "내가 쓴 글" })
    .getByRole("listitem")
    .filter({ hasText: marker });
  await expect(mine).toContainText("다른 회원에게 보이지 않아요");

  await mine.getByRole("link").click();
  await author.page.waitForURL(`**/post/${postId}`);

  await expect(notice(author.page)).toContainText(
    "이 글은 다른 회원에게 보이지 않아요.",
  );
  await expect(
    notice(author.page).getByRole("link", { name: "109" }),
  ).toBeVisible();

  await closeAll(author);
});

test("US1-AC4 우려 표현이 든 글은 다른 회원에게 보이고 작성자에게만 접을 수 있는 도움 안내가 보인다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const other = await newMember(browser, "b");
  const marker = `우려 글 ${Date.now()}`;
  const postId = await writePost(author, `[실패] ${CONCERN_TEXT} ${marker}`);

  await author.page.goto(`/post/${postId}`);

  const banner = notice(author.page);
  await expect(banner.getByRole("link", { name: "109" })).toBeVisible();
  await expect(banner).not.toContainText("보이지 않아요");
  await banner.getByRole("button", { name: "접기" }).click();
  await expect(banner.getByRole("link", { name: "109" })).toHaveCount(0);

  await other.page.goto(`/post/${postId}`);

  await expect(other.page.getByText(marker)).toBeVisible();
  await expect(notice(other.page)).toHaveCount(0);

  await closeAll(author, other);
});

test("US1-AC5 위기 표현이 든 댓글은 작성자에게 도움 안내가 보이고 다른 회원에게는 가려진 자리와 답글만 보인다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const commenter = await newMember(browser, "b");
  const postId = await writePost(author, `[실패] 댓글이 달릴 글 ${Date.now()}`);
  await commenter.page.goto(`/post/${postId}`);

  await commenter.page.getByLabel("댓글", { exact: true }).fill(CRISIS_TEXT);
  await commenter.page.getByRole("button", { name: "등록" }).click();

  const mine = commenter.page.getByRole("article", {
    name: `${commenter.nickname}의 댓글`,
  });
  await expect(mine).toContainText(CRISIS_TEXT);
  await expect(mine).toContainText("다른 회원에게 보이지 않아요");
  await expect(notice(commenter.page)).toContainText(
    "내 댓글 가운데 다른 회원에게 보이지 않는 것이 있어요.",
  );
  await expect(
    notice(commenter.page).getByRole("link", { name: "109" }),
  ).toBeVisible();

  await author.page.goto(`/post/${postId}`);

  await expect(
    author.page.getByRole("article", { name: "가려진 댓글" }),
  ).toContainText("가려진 댓글이에요");
  await expect(author.page.getByRole("main")).not.toContainText(CRISIS_TEXT);
  await expect(author.page.getByRole("main")).not.toContainText(
    commenter.nickname,
  );
  await expect(notice(author.page)).toHaveCount(0);

  await closeAll(author, commenter);
});

test("US1-AC6 홈을 열어 둔 채 내 글이 위기로 판정되면 새로고침 없이 도움 안내 토스트가 오고 알림 목록에 남는다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const bell = author.page.getByRole("link", { name: /^알림/ });
  await expect(bell).toHaveAttribute("data-stream-status", "open");
  const marker = `토스트 글 ${Date.now()}`;

  const postId = await writePost(author, `[실패] ${CRISIS_TEXT} ${marker}`);

  const toast = author.page
    .getByRole("region", { name: "새 알림" })
    .getByRole("button", { name: /마음이 많이 힘드신가요/ });
  await expect(toast).toBeVisible();
  await expect(toast).not.toContainText(marker);
  await expect(bell).toHaveAccessibleName("알림, 안 읽은 알림 1개");

  await toast.click();

  // 숨긴 글이어도 작성자는 알림에서 자기 글로 갈 수 있다
  await author.page.waitForURL(`**/post/${postId}`);
  await expect(notice(author.page)).toBeVisible();

  await bell.click();
  await author.page.waitForURL("**/notifications");
  const item = author.page
    .getByRole("list", { name: "알림" })
    .getByRole("listitem")
    .first();
  await expect(item).toContainText("마음이 많이 힘드신가요?");

  await closeAll(author);
});

test("US2-AC1 AI 분류가 실패해도 위기 표현이 든 글은 숨겨지고 작성자는 도움 안내를 본다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const other = await newMember(browser, "b");
  const marker = `분류 실패 글 ${Date.now()}`;
  // 가짜 분류기가 이 글에는 계속 실패한다. 키워드 규칙만으로 판정된다.
  const postId = await writePost(
    author,
    `[실패] [위험분류실패] ${CRISIS_TEXT} ${marker}`,
  );

  await author.page.goto(`/post/${postId}`);

  await expect(notice(author.page)).toContainText(
    "이 글은 다른 회원에게 보이지 않아요.",
  );
  await expect(
    notice(author.page).getByRole("link", { name: "109" }),
  ).toBeVisible();

  await other.page.goto("/home");
  await waitForHomeLoaded(other.page);

  await expect(feed(other.page)).not.toContainText(marker);

  await closeAll(author, other);
});

test("US2-AC3 AI 분류가 실패해도 글은 오류 없이 올라가고 그대로 보인다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const marker = `분류가 늦는 글 ${Date.now()}`;

  await writePostOnScreen(author, `[위험분류실패:2] ${marker}`);

  await expect(author.page.getByText(marker)).toBeVisible();
  await expect(author.page.getByRole("main").getByRole("alert")).toHaveCount(0);
  await expect(notice(author.page)).toHaveCount(0);

  await closeAll(author);
});

test("AI만 위기로 알아본 글은 분류가 끝나면 숨겨지고 작성자에게 도움 안내 토스트가 온다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const other = await newMember(browser, "b");
  const bell = author.page.getByRole("link", { name: /^알림/ });
  await expect(bell).toHaveAttribute("data-stream-status", "open");
  const marker = `목록에 없는 표현 ${Date.now()}`;

  // 키워드 목록에는 없지만 가짜 분류기가 위기로 답한다
  const postId = await writePost(author, `[실패] [위기] ${marker}`);

  await expect(
    author.page
      .getByRole("region", { name: "새 알림" })
      .getByRole("button", { name: /마음이 많이 힘드신가요/ }),
  ).toBeVisible({ timeout: 15_000 });

  await other.page.goto(`/post/${postId}`);

  await expect(other.page.getByText("삭제된 글이에요.")).toBeVisible();

  await closeAll(author, other);
});

test("US3-AC1, US3-AC2 다른 회원의 글과 댓글을 사유와 함께 신고하고, 다시 신고하면 이미 신고했다고 안내받는다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const reporter = await newMember(browser, "b");
  const postId = await writePost(author, `[실패] 평범한 글 ${Date.now()}`);
  const commentResponse = await call(
    author.page,
    `/api/posts/${postId}/comments`,
    { content: "글쓴이가 단 댓글" },
  );
  expect(commentResponse.status()).toBe(201);
  await reporter.page.goto(`/post/${postId}`);

  // 글 신고: 기타를 골라 설명을 적는다
  const article = reporter.page.getByRole("article").first();
  await article.getByRole("button", { name: "신고" }).first().click();
  const dialog = reporter.page.getByRole("alertdialog");
  await expect(dialog).toContainText("이 글을 신고할까요?");
  await dialog.getByRole("radio", { name: "기타" }).check();
  await dialog.getByRole("textbox").fill("확인용 설명이에요");
  await dialog.getByRole("button", { name: "신고하기" }).click();

  await expect(reporter.page.getByRole("status").first()).toHaveText(
    "신고가 접수됐어요",
  );
  await expect(dialog).toHaveCount(0);

  // 댓글 신고
  const comment = reporter.page.getByRole("article", {
    name: `${author.nickname}의 댓글`,
  });
  await comment.getByRole("button", { name: "신고" }).click();
  await dialog.getByRole("radio", { name: "욕설이나 비방이에요" }).check();
  await dialog.getByRole("button", { name: "신고하기" }).click();
  await expect(comment.getByRole("status")).toHaveText("신고가 접수됐어요");

  // 새로고침하면 신고 버튼이 다시 보인다(신고했다는 것을 서버가 알려 주지 않는다). 다시 신고하면 안내를 받는다
  await reporter.page.reload();
  await article.getByRole("button", { name: "신고" }).first().click();
  await dialog.getByRole("radio", { name: "광고나 도배예요" }).check();
  await dialog.getByRole("button", { name: "신고하기" }).click();

  await expect(reporter.page.getByRole("status").first()).toHaveText(
    "이미 신고한 글이에요",
  );
  // 신고해도 글은 그대로 보인다
  await expect(reporter.page.getByText("글쓴이가 단 댓글")).toBeVisible();

  await closeAll(author, reporter);
});

test("US3-AC3 내 글과 내 댓글에는 신고가 없다", async ({ browser }) => {
  const author = await newMember(browser, "a");
  const postId = await writePost(author, `[실패] 내 글 ${Date.now()}`);
  const response = await call(author.page, `/api/posts/${postId}/comments`, {
    content: "내 댓글",
  });
  expect(response.status()).toBe(201);

  await author.page.goto(`/post/${postId}`);

  await expect(author.page.getByText("내 댓글")).toBeVisible();
  await expect(author.page.getByRole("button", { name: "신고" })).toHaveCount(
    0,
  );
  // API로 시도해도 거절된다
  const direct = await call(author.page, "/api/reports", {
    targetType: "POST",
    targetId: postId,
    reason: "SPAM",
  });
  expect(direct.status()).toBe(403);

  await closeAll(author);
});
