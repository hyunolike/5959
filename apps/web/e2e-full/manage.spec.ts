import { expect, test, type Browser, type Page } from "@playwright/test";

import { postArticle } from "./support/detail";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 글과 댓글 수정, 삭제(US4)를 확인한다.
// 글은 바로 분석되는 머리말(`[불안:낮음]`: 불안, 최대 HP 10)로 쓴다. `[실패]` 글은 재시도
// 대기열에 남아 다른 스펙을 늦추므로 쓰지 않는다. 작성자와 댓글 단 회원은 서로 다른 브라우저
// 컨텍스트(쿠키)를 쓰는 다른 회원이다.

const APP_ORIGIN = "http://localhost:3000";

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

/** 새 컨텍스트에서 가입과 온보딩을 마친 회원의 페이지와 닉네임. */
async function newMember(
  browser: Browser,
  prefix: string,
): Promise<{ page: Page; nickname: string }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const nickname = uniqueNickname(prefix);
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(uniqueEmail(`manage-${prefix}`));
  await page.getByLabel("비밀번호").fill("abcd1234");
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption("DESIGN");
  await page.getByLabel("경력").selectOption("YEAR_3");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await expect(page.getByRole("heading")).toContainText("님, 반가워요");
  return { page, nickname };
}

/** 지금 로그인한 회원으로 같은 출처 BFF를 부른다. */
async function call(
  page: Page,
  method: "GET" | "POST",
  path: string,
  data?: unknown,
) {
  return page.request.fetch(path, {
    method,
    headers: { Origin: APP_ORIGIN },
    data,
  });
}

function monster(page: Page) {
  return postArticle(page).locator('[aria-label="몬스터"]');
}

function hpText(page: Page) {
  return monster(page).getByText(/^HP \d+\/\d+$/);
}

function counts(page: Page) {
  return page.locator('[aria-label="공감과 댓글 수"]');
}

/** 바로 분석되는 글을 쓰고 몬스터가 생길 때까지 상세에서 기다린다. */
async function createAnalysedPost(
  page: Page,
  content: string,
): Promise<number> {
  const response = await call(page, "POST", "/api/posts", {
    content,
    commentTone: "COMFORT_ME",
  });
  expect(response.status()).toBe(201);
  const postId = ((await response.json()) as { data: { postId: number } }).data
    .postId;
  await page.goto(`/post/${postId}`);
  await expect(hpText(page)).toHaveText("HP 10/10", { timeout: 30_000 });
  return postId;
}

async function createComment(
  page: Page,
  postId: number,
  content: string,
  parentId?: number,
): Promise<number> {
  const response = await call(page, "POST", `/api/posts/${postId}/comments`, {
    content,
    parentId,
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { data: { commentId: number } }).data
    .commentId;
}

/** 최신순 피드 앞 50개의 글 ID. */
async function latestFeedIds(page: Page): Promise<number[]> {
  const response = await call(page, "GET", "/api/feed?size=50");
  expect(response.status()).toBe(200);
  const body = (await response.json()) as {
    data: { items: { postId: number }[] };
  };
  return body.data.items.map((item) => item.postId);
}

test("US4-AC1 내 글의 본문과 말투를 고치면 반영되고 몬스터의 HP와 상태는 그대로다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const original = `[불안:낮음] 고치기 전 고민 ${Date.now()}`;
  const postId = await createAnalysedPost(author.page, original);
  const commenter = await newMember(browser, "b");
  await createComment(commenter.page, postId, "힘내요");

  const page = author.page;
  await page.reload();
  await expect(hpText(page)).toHaveText("HP 7/10");
  await page.getByRole("link", { name: "수정" }).click();
  await page.waitForURL(`**/post/${postId}/edit`);

  // 작성 폼이 지금 본문과 말투로 채워져 있다
  const textarea = page.getByLabel("고민");
  await expect(textarea).toHaveValue(original);
  await expect(
    page.getByRole("button", { name: "무조건 위로해주기" }),
  ).toHaveAttribute("aria-pressed", "true");

  const edited = `[기쁨:높음] 고친 고민 ${Date.now()}`;
  await textarea.fill(edited);
  await page.getByRole("button", { name: "웃겨주기" }).click();
  await page.getByRole("button", { name: "고치기" }).click();

  await page.waitForURL(`**/post/${postId}`);
  await expect(page.getByText(edited)).toBeVisible();
  await expect(page.getByText("웃겨주기")).toBeVisible();
  // 다른 감정처럼 보이게 고쳐도 몬스터는 다시 분석하지 않는다
  await expect(monster(page).getByText("불안")).toBeVisible();
  await expect(hpText(page)).toHaveText("HP 7/10");

  await page.reload();
  await expect(page.getByText(edited)).toBeVisible();
  await expect(hpText(page)).toHaveText("HP 7/10");
});

test("US4-AC2 내 글을 확인 대화상자에서 지우면 홈으로 가고 피드와 상세에서 사라진다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const content = `[불안:낮음] 지울 고민 ${Date.now()}`;
  const postId = await createAnalysedPost(author.page, content);
  const viewer = await newMember(browser, "v");
  expect(await latestFeedIds(viewer.page)).toContain(postId);

  const page = author.page;
  // 다른 회원에게는 수정과 삭제가 보이지 않는다(US4-AC4)
  await viewer.page.goto(`/post/${postId}`);
  await expect(hpText(viewer.page)).toHaveText("HP 10/10");
  await expect(viewer.page.getByRole("link", { name: "수정" })).toHaveCount(0);

  await page.getByRole("button", { name: "삭제", exact: true }).click();
  const dialog = page.getByRole("alertdialog", { name: "글을 지울까요?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "삭제하기" }).click();

  await page.waitForURL("**/home");
  await expect(page.getByText(content)).toHaveCount(0);
  expect(await latestFeedIds(viewer.page)).not.toContain(postId);

  await viewer.page.goto(`/post/${postId}`);
  await expect(viewer.page.getByText("삭제된 글이에요.")).toBeVisible();
});

test("US4-AC3 내 댓글을 고치고, 원 댓글을 지우면 답글도 함께 사라진다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const postId = await createAnalysedPost(
    author.page,
    `[불안:낮음] 댓글 받을 고민 ${Date.now()}`,
  );
  const commenter = await newMember(browser, "c");
  const rootId = await createComment(commenter.page, postId, "원 댓글이에요");
  await createComment(author.page, postId, "작성자 답글이에요", rootId);
  await createComment(author.page, postId, "작성자 원 댓글이에요");

  const page = commenter.page;
  await page.goto(`/post/${postId}`);
  await expect(counts(page).getByText("댓글 3")).toBeVisible();
  const mine = page.getByRole("article", {
    name: `${commenter.nickname}의 댓글`,
  });
  const authorsReply = page.getByRole("article", {
    name: `${author.nickname}의 답글`,
  });
  // 남의 댓글에는 수정과 삭제 메뉴가 없다
  await expect(authorsReply.getByRole("button", { name: "수정" })).toHaveCount(
    0,
  );

  // 고치기
  await mine.getByRole("button", { name: "수정" }).first().click();
  const editor = page.getByLabel("댓글 수정");
  await expect(editor).toHaveValue("원 댓글이에요");
  await editor.fill("고친 원 댓글이에요");
  await page.getByRole("button", { name: "저장" }).click();
  await expect(mine.getByText("고친 원 댓글이에요")).toBeVisible();
  await expect(editor).toHaveCount(0);

  // 지우기: 답글도 함께 지워지고 댓글 수가 2 준다
  await mine.getByRole("button", { name: "삭제" }).first().click();
  const dialog = page.getByRole("alertdialog", { name: "댓글을 지울까요?" });
  await expect(dialog).toContainText("답글도 함께 지워져요");
  await dialog.getByRole("button", { name: "삭제하기" }).click();

  await expect(dialog).toHaveCount(0);
  await expect(page.getByText("고친 원 댓글이에요")).toHaveCount(0);
  await expect(page.getByText("작성자 답글이에요")).toHaveCount(0);
  await expect(page.getByText("작성자 원 댓글이에요")).toBeVisible();
  await expect(counts(page).getByText("댓글 1")).toBeVisible();

  await page.reload();
  await expect(page.getByText("작성자 답글이에요")).toHaveCount(0);
  await expect(counts(page).getByText("댓글 1")).toBeVisible();
});
