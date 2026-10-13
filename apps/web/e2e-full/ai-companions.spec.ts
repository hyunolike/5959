import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";

// infra/compose.e2e.yaml로 띄운 실제 API와 DB를 상대로 AI 친구(009)를 확인한다.
// e2e 프로필은 기능을 켠 채 돌고, 기다리는 시간은 5초, 주기는 2초다. 가짜 댓글 쓰기는 본문에
// "[AI친구]"가 있는 글에만 답한다(다른 시나리오의 댓글 수와 몬스터 HP가 흔들리지 않게).
// 글은 감정 분석이 끝나지 않게 "[실패]"로 시작해, 몬스터 알림이 섞이지 않게 한다.

const APP_ORIGIN = "http://localhost:3000";
const PASSWORD = "abcd1234";
/** 기다리는 시간(5초), 위험 분류, 주기(2초)를 넉넉히 덮는다. */
const REACTION_TIMEOUT = 45_000;

interface Member {
  context: BrowserContext;
  page: Page;
  nickname: string;
}

interface CommentNode {
  author: { nickname: string; ai: boolean } | null;
  content: string | null;
}

function unique(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

/** 새 컨텍스트에서 가입과 온보딩을 마친 회원. */
async function newMember(browser: Browser, prefix: string): Promise<Member> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const nickname = unique(prefix);
  await page.goto("/signup");
  await page
    .getByLabel("이메일")
    .fill(`ai-${prefix}-${Date.now()}-${unique("")}@example.com`);
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
    method: data === undefined ? "GET" : "POST",
    headers: { Origin: APP_ORIGIN },
    data,
  });
}

async function writePost(author: Member, content: string): Promise<number> {
  const response = await call(author.page, "/api/posts", {
    content: `${content} ${Date.now()}`,
    commentTone: "COMFORT_ME",
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { data: { postId: number } }).data.postId;
}

async function commentsOf(member: Member, postId: number) {
  const response = await call(member.page, `/api/posts/${postId}/comments`);
  expect(response.status()).toBe(200);
  return ((await response.json()) as { data: { items: CommentNode[] } }).data
    .items;
}

/** AI 친구가 이 글에 댓글을 [count]개 남길 때까지 기다린다. */
async function waitForCompanion(member: Member, postId: number, count = 1) {
  await expect
    .poll(
      async () =>
        (await commentsOf(member, postId)).filter((item) => item.author?.ai)
          .length,
      { timeout: REACTION_TIMEOUT, intervals: [1_000] },
    )
    .toBe(count);
}

function companionComment(page: Page): Locator {
  return page.getByRole("article", { name: /^AI 친구 .+ 오구의 댓글$/ });
}

const members: Member[] = [];

test.afterEach(async () => {
  await Promise.all(members.splice(0).map((member) => member.context.close()));
});

test("US1-AC1, US2-AC1, US2-AC4 댓글이 없는 글에 AI 친구가 답하고 AI 친구라고 표시되며 표시를 누르면 안내가 나온다", async ({
  browser,
}) => {
  const author = await newMember(browser, "ai");
  members.push(author);
  const postId = await writePost(author, "[실패] [AI친구] 회의가 길었어요");

  await waitForCompanion(author, postId);
  await author.page.goto(`/post/${postId}`);

  const comment = companionComment(author.page);
  await expect(comment).toBeVisible();
  // 가짜 쓰기는 말투의 이름을 댓글에 넣는다
  await expect(comment).toContainText("무조건 위로해주기 말투로 전해요");
  const badge = comment.getByRole("link", { name: "AI 친구" });
  await expect(badge).toBeVisible();
  // 직군과 경력을 꾸며 보이지 않는다
  await expect(comment).not.toContainText(/년차|디자인|개발/);
  // 누르는 영역은 44px 이상이고 키보드로 닿는다
  const hitHeight = await badge.evaluate(
    (element) => parseFloat(getComputedStyle(element, "::after").height) || 0,
  );
  expect(hitHeight).toBeGreaterThanOrEqual(44);
  await badge.focus();
  await expect(badge).toBeFocused();

  await badge.click();
  await author.page.waitForURL("**/ai-friends");
  await expect(
    author.page.getByRole("heading", { level: 1, name: "AI 친구" }),
  ).toBeVisible();
  for (const fact of [
    "AI가 쓴 댓글이에요",
    "댓글이 없는 글에 먼저 답해요",
    "답글에는 답하지 못해요",
    "전문 상담이 아니에요",
  ]) {
    await expect(
      author.page.getByRole("heading", { name: fact }),
    ).toBeVisible();
  }
});

test("US1-AC6, US2-AC2 글쓴이의 알림 목록에 AI 친구가 한 일임이 드러난다", async ({
  browser,
}) => {
  const author = await newMember(browser, "ai");
  members.push(author);
  const postId = await writePost(author, "[실패] [AI친구] 오늘도 야근이에요");
  await waitForCompanion(author, postId);

  await author.page.goto("/notifications");

  const items = author.page
    .getByRole("list", { name: "알림" })
    .getByRole("listitem");
  await expect(
    items.filter({ hasText: /AI 친구 .+ 오구가 내 글에 댓글을 남겼어요/ }),
  ).toHaveCount(1);
  await expect(
    items.filter({ hasText: /AI 친구 .+ 오구가 공감했어요/ }),
  ).toHaveCount(1);
});

test("US1-AC2, US3-AC1 다른 회원이 먼저 댓글을 단 글과 우려로 판정된 글에는 AI 친구가 답하지 않는다", async ({
  browser,
}) => {
  const author = await newMember(browser, "ai");
  const other = await newMember(browser, "hu");
  members.push(author, other);
  const answered = await writePost(author, "[실패] [AI친구] 누가 먼저 답한 글");
  const response = await call(other.page, `/api/posts/${answered}/comments`, {
    content: "제가 먼저 왔어요",
  });
  expect(response.status()).toBe(201);
  const concern = await writePost(author, "[실패] [AI친구] [우려] 판정된 글");
  // 같은 때에 쓴 다른 글이 답을 받으면, 앞의 두 글도 차례가 지난 것이다
  const control = await writePost(author, "[실패] [AI친구] 답을 받는 글");

  await waitForCompanion(author, control);

  const onAnswered = await commentsOf(author, answered);
  expect(onAnswered.map((item) => item.author?.ai)).toEqual([false]);
  expect(await commentsOf(author, concern)).toEqual([]);
});

// 012: e2e 프로필에서는 반응할 친구의 수를 본문의 [AI친구:3]으로 정하고, 뒤따르는 친구는 2초에서 4초 뒤에 온다.
test("012 US1-AC5 수가 3으로 정해진 글에 서로 다른 AI 친구 셋이 차례로 답한다", async ({
  browser,
}) => {
  const author = await newMember(browser, "ai");
  members.push(author);
  const postId = await writePost(
    author,
    "[실패] [AI친구:3] 오늘 회의가 길었어요",
  );

  await waitForCompanion(author, postId, 3);

  const comments = await commentsOf(author, postId);
  const names = comments.map((item) => item.author?.nickname);
  expect(new Set(names)).toEqual(
    new Set(["다정한 오구", "씩씩한 오구", "차분한 오구"]),
  );
  // 친구마다 다른 말을 한다
  expect(new Set(comments.map((item) => item.content)).size).toBe(3);
  await author.page.goto(`/post/${postId}`);
  await expect(companionComment(author.page)).toHaveCount(3);
});

test("012 US2-AC1 첫 AI 친구가 답한 뒤 사람이 댓글을 달면 남은 AI 친구는 답하지 않는다", async ({
  browser,
}) => {
  const author = await newMember(browser, "ai");
  const other = await newMember(browser, "hu");
  members.push(author, other);
  const postId = await writePost(author, "[실패] [AI친구:3] 누가 끼어드는 글");
  await waitForCompanion(author, postId, 1);
  const response = await call(other.page, `/api/posts/${postId}/comments`, {
    content: "저도 같은 날이 있었어요",
  });
  expect(response.status()).toBe(201);
  // 같은 때에 쓴 다른 글이 셋을 다 받으면, 앞 글의 남은 차례도 지난 것이다
  const control = await writePost(author, "[실패] [AI친구:3] 셋이 답하는 글");

  await waitForCompanion(author, control, 3);

  const comments = await commentsOf(author, postId);
  // 사람이 끼어드는 순간 둘째 친구가 이미 쓰고 있었을 수 있다. 셋째는 오지 않는다
  expect(comments.filter((item) => item.author?.ai).length).toBeLessThan(3);
  expect(comments.filter((item) => item.author?.ai === false)).toHaveLength(1);
});

test("US2-AC5 AI 친구와 헷갈리는 닉네임은 쓸 수 없다고 안내한다", async ({
  browser,
}) => {
  const me = await newMember(browser, "ai");
  members.push(me);
  await me.page.goto("/my/edit");

  for (const nickname of ["다정한오구", "오구AI"]) {
    await me.page.getByLabel("닉네임").fill(nickname);
    await expect(
      me.page.getByText("AI 친구와 헷갈릴 수 있어 쓸 수 없는 닉네임입니다."),
    ).toBeVisible();
  }
  await me.page.getByLabel("닉네임").fill(unique("Kai"));
  await expect(me.page.getByText("사용할 수 있는 닉네임입니다.")).toBeVisible();
});
