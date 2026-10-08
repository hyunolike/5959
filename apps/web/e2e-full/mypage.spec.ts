import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";

// infra/compose.e2e.yaml로 띄운 실제 API와 DB를 상대로 마이페이지(004 US3)를 확인한다.
// 나와 다른 회원은 서로 다른 브라우저 컨텍스트(쿠키)를 쓴다.

const APP_ORIGIN = "http://localhost:3000";
const PASSWORD = "abcd1234";

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
  await page.getByLabel("이메일").fill(uniqueEmail(`my-${prefix}`));
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
async function call(
  page: Page,
  path: string,
  data?: unknown,
  method: "POST" | "DELETE" = "POST",
) {
  return page.request.fetch(path, {
    method,
    headers: { Origin: APP_ORIGIN },
    data,
  });
}

/** 바로 분석되는 글(불안, 최대 HP 10)을 쓴다. 본문은 머리말 뒤에 [label]을 붙인 것이다. */
async function writePost(author: Member, label: string): Promise<number> {
  const response = await call(author.page, "/api/posts", {
    content: `[불안:낮음] ${label}`,
    commentTone: "COMFORT_ME",
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { data: { postId: number } }).data.postId;
}

/** 감정 분석이 끝나 몬스터가 붙을 때까지 기다린다. */
async function waitForMonster(member: Member, postId: number) {
  await expect
    .poll(
      async () => {
        const response = await member.page.request.get(`/api/posts/${postId}`);
        const body = (await response.json()) as {
          data: { monster: unknown } | null;
        };
        return body.data?.monster ?? null;
      },
      { timeout: 30_000 },
    )
    .not.toBeNull();
}

async function deletePost(member: Member, postId: number) {
  const response = await call(
    member.page,
    `/api/posts/${postId}`,
    undefined,
    "DELETE",
  );
  expect(response.status()).toBe(204);
}

async function comment(
  member: Member,
  postId: number,
  content: string,
  parentId?: number,
): Promise<number> {
  const response = await call(member.page, `/api/posts/${postId}/comments`, {
    content,
    ...(parentId === undefined ? {} : { parentId }),
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { data: { commentId: number } }).data
    .commentId;
}

async function deleteComment(member: Member, commentId: number) {
  const response = await call(
    member.page,
    `/api/comments/${commentId}`,
    undefined,
    "DELETE",
  );
  expect(response.status()).toBe(204);
}

async function like(member: Member, postId: number) {
  const response = await call(member.page, `/api/posts/${postId}/likes`);
  expect(response.ok()).toBe(true);
}

async function unlike(member: Member, postId: number) {
  const response = await call(
    member.page,
    `/api/posts/${postId}/likes/me`,
    undefined,
    "DELETE",
  );
  expect(response.ok()).toBe(true);
}

function tab(page: Page, name: string): Locator {
  return page.getByRole("tab", { name });
}

function items(page: Page, listName: string): Locator {
  return page.getByRole("list", { name: listName }).getByRole("listitem");
}

async function closeAll(...members: Member[]) {
  await Promise.all(members.map((member) => member.context.close()));
}

test("US3-AC1 내가 쓴 글 탭은 내 글만 최신순으로 보이고 항목마다 본문, 몬스터와 HP, 공감 수, 댓글 수, 작성 시각이 있다", async ({
  browser,
}) => {
  const me = await newMember(browser, "a");
  const other = await newMember(browser, "b");
  const older = await writePost(me, "먼저 쓴 글");
  const removed = await writePost(me, "지울 글");
  const newer = await writePost(me, "나중에 쓴 글");
  await writePost(other, "남이 쓴 글");
  await deletePost(me, removed);
  await waitForMonster(me, older);
  await waitForMonster(me, newer);
  await like(other, newer);
  await comment(other, newer, "응원해요");

  await me.page.goto("/my");

  await expect(tab(me.page, "내가 쓴 글")).toHaveAttribute(
    "aria-selected",
    "true",
  );
  const posts = items(me.page, "내가 쓴 글");
  await expect(posts).toHaveCount(2);
  await expect(posts.nth(0)).toContainText("나중에 쓴 글");
  await expect(posts.nth(1)).toContainText("먼저 쓴 글");
  const newest = posts.nth(0);
  await expect(newest.getByRole("link")).toHaveAttribute(
    "href",
    `/post/${newer}`,
  );
  await expect(newest).toContainText(me.nickname);
  // 공감 1과 첫 댓글 3으로 HP가 10에서 6이 됐다.
  await expect(newest).toContainText("HP 6/10");
  await expect(newest).toContainText("공감 1");
  await expect(newest).toContainText("댓글 1");
  await expect(newest.locator("time")).toHaveAttribute(
    "datetime",
    /^\d{4}-\d{2}-\d{2}T/,
  );
  await expect(posts.nth(1)).toContainText("HP 10/10");
  const panel = me.page.getByRole("tabpanel");
  await expect(panel).not.toContainText("지울 글");
  await expect(panel).not.toContainText("남이 쓴 글");

  await newest.getByRole("link").click();
  await me.page.waitForURL(`**/post/${newer}`);

  await closeAll(me, other);
});

test("US3-AC2 내 댓글 탭은 내 댓글과 답글을 최신순으로 보이고 누르면 그 글로 간다", async ({
  browser,
}) => {
  const me = await newMember(browser, "a");
  const other = await newMember(browser, "b");
  const postId = await writePost(other, "댓글이 달릴 글");
  const goneId = await writePost(other, "곧 지워질 글");
  await comment(me, postId, "내 첫 댓글");
  const othersComment = await comment(other, postId, "남의 댓글");
  await comment(me, postId, "내가 단 답글", othersComment);
  const removed = await comment(me, postId, "지울 댓글");
  await deleteComment(me, removed);
  await comment(me, goneId, "지워질 글에 단 댓글");
  await deletePost(other, goneId);

  await me.page.goto("/my?tab=comments");

  await expect(tab(me.page, "내 댓글")).toHaveAttribute(
    "aria-selected",
    "true",
  );
  const comments = items(me.page, "내 댓글");
  await expect(comments).toHaveCount(2);
  await expect(comments.nth(0)).toContainText("내가 단 답글");
  await expect(comments.nth(0)).toContainText("답글");
  await expect(comments.nth(1)).toContainText("내 첫 댓글");
  for (const item of await comments.all()) {
    await expect(item).toContainText("댓글이 달릴 글");
    await expect(item.locator("time")).toHaveAttribute(
      "datetime",
      /^\d{4}-\d{2}-\d{2}T/,
    );
  }
  const panel = me.page.getByRole("tabpanel");
  await expect(panel).not.toContainText("지울 댓글");
  await expect(panel).not.toContainText("남의 댓글");
  await expect(panel).not.toContainText("지워질 글에 단 댓글");

  await comments.nth(1).getByRole("link").click();

  await me.page.waitForURL(`**/post/${postId}`);
  await expect(me.page.getByText("내 첫 댓글")).toBeVisible();

  await closeAll(me, other);
});

test("US3-AC3 공감한 글 탭은 공감한 순서의 최신순이고 취소한 공감과 지운 글은 없다", async ({
  browser,
}) => {
  const me = await newMember(browser, "a");
  const other = await newMember(browser, "b");
  const first = await writePost(other, "가장 먼저 쓴 글");
  const second = await writePost(other, "두 번째로 쓴 글");
  const cancelled = await writePost(other, "공감을 취소할 글");
  const gone = await writePost(other, "공감한 뒤 지워질 글");
  // 쓴 순서와 다르게 공감한다.
  await like(me, second);
  await like(me, cancelled);
  await like(me, gone);
  await like(me, first);
  await unlike(me, cancelled);
  await deletePost(other, gone);

  await me.page.goto("/my");
  await tab(me.page, "공감한 글").click();

  await expect(me.page).toHaveURL(/\/my\?tab=likes$/);
  const liked = items(me.page, "공감한 글");
  await expect(liked).toHaveCount(2);
  await expect(liked.nth(0)).toContainText("가장 먼저 쓴 글");
  await expect(liked.nth(1)).toContainText("두 번째로 쓴 글");
  await expect(liked.nth(0)).toContainText(other.nickname);
  await expect(liked.nth(0)).toContainText("공감 1");
  await expect(liked.nth(0)).toContainText("내가 공감함");
  await expect(liked.nth(0).getByRole("link")).toHaveAttribute(
    "href",
    `/post/${first}`,
  );

  // 새로고침해도 고른 탭이 남는다.
  await me.page.reload();
  await expect(tab(me.page, "공감한 글")).toHaveAttribute(
    "aria-selected",
    "true",
  );
  await expect(liked).toHaveCount(2);

  await closeAll(me, other);
});

test("US3-AC4 활동이 없는 회원은 세 탭 모두 비어 있다는 안내와 글쓰기, 피드 보기를 본다", async ({
  browser,
}) => {
  const me = await newMember(browser, "a");

  await me.page.goto("/my");

  const panel = me.page.getByRole("tabpanel");
  const cases = [
    ["내가 쓴 글", "아직 쓴 글이 없어요."],
    ["내 댓글", "아직 남긴 댓글이 없어요."],
    ["공감한 글", "아직 공감한 글이 없어요."],
  ] as const;
  for (const [name, message] of cases) {
    await tab(me.page, name).click();
    await expect(panel).toContainText(message);
    await expect(panel.getByRole("link", { name: "글쓰기" })).toHaveAttribute(
      "href",
      "/write",
    );
    await expect(
      panel.getByRole("link", { name: "피드 보기" }),
    ).toHaveAttribute("href", "/home");
  }

  await panel.getByRole("link", { name: "글쓰기" }).click();
  await me.page.waitForURL("**/write");

  await closeAll(me);
});
