import { expect, test, type Browser, type Page } from "@playwright/test";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. 글은 바로 분석되는
// 머리말(`[불안:낮음]`: 불안, 최대 HP 10)로 쓴다. `[실패]` 글은 24시간 동안 재시도
// 대기열에 남아 다른 스펙의 재시도를 늦추므로 쓰지 않는다.
// 작성자와 공격자는 서로 다른 브라우저 컨텍스트(쿠키)를 쓰는 다른 회원이다.

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
  await page.getByLabel("이메일").fill(uniqueEmail(`attack-${prefix}`));
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
  method: "POST" | "DELETE",
  path: string,
  data?: unknown,
) {
  return page.request.fetch(path, {
    method,
    headers: { Origin: APP_ORIGIN },
    data,
  });
}

/** 바로 분석되는 글(불안, 최대 HP 10)을 쓰고 몬스터가 생길 때까지 상세에서 기다린다. */
async function createAnalysedPost(page: Page, label: string): Promise<number> {
  const response = await call(page, "POST", "/api/posts", {
    content: `[불안:낮음] ${label} ${Date.now()}`,
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
): Promise<number> {
  const response = await call(page, "POST", `/api/posts/${postId}/comments`, {
    content,
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { data: { commentId: number } }).data
    .commentId;
}

function monster(page: Page) {
  return page.locator('[aria-label="몬스터"]');
}

/** 글 아래의 공감 수와 댓글 수 줄. 댓글 공감 버튼의 "공감 N"과 섞이지 않게 범위를 좁힌다. */
function counts(page: Page) {
  return page.locator('[aria-label="공감과 댓글 수"]');
}

/** 댓글이나 답글 하나. 글 전체를 감싼 이름 없는 article은 빼고 고른다. */
function commentArticle(page: Page, text: string) {
  return page
    .getByRole("article", { name: /의 (댓글|답글)$/ })
    .filter({ hasText: text });
}

function hpText(page: Page) {
  return monster(page).getByText(/^HP \d+\/\d+$/);
}

function postLikeButton(page: Page) {
  return page.getByRole("button", { name: /^공감 \d+$/ });
}

function commentForm(page: Page) {
  return page.getByLabel("댓글", { exact: true });
}

async function writeCommentInUi(page: Page, content: string) {
  await commentForm(page).fill(content);
  await page.getByRole("button", { name: "등록" }).click();
  await expect(commentArticle(page, content)).toBeVisible();
}

test("US3-AC1 다른 회원의 글에 공감하면 공감 수가 1 늘고 HP가 1 줄며, 다시 공감할 수 없다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const postId = await createAnalysedPost(author.page, "공감 받을 글");
  const attacker = await newMember(browser, "b");

  await attacker.page.goto(`/post/${postId}`);
  await expect(hpText(attacker.page)).toHaveText("HP 10/10");
  await attacker.page.getByRole("button", { name: "공감 0" }).click();

  const liked = attacker.page.getByRole("button", { name: "공감 1" });
  await expect(liked).toHaveAttribute("aria-pressed", "true");
  await expect(hpText(attacker.page)).toHaveText("HP 9/10");

  // 응답이 오기 전에 새로고침하면 요청이 끊길 수 있다. 버튼이 다시 눌릴 수 있을 때까지 기다린다.
  await expect(liked).toBeEnabled();
  // 새로고침해도 서버 값이 같다
  await attacker.page.reload();
  await expect(liked).toHaveAttribute("aria-pressed", "true");
  await expect(hpText(attacker.page)).toHaveText("HP 9/10");

  // 같은 글에 다시 공감할 수 없다
  const again = await call(attacker.page, "POST", `/api/posts/${postId}/likes`);
  expect(again.status()).toBe(409);
  expect(((await again.json()) as { error: { code: string } }).error.code).toBe(
    "ALREADY_LIKED",
  );

  // 취소해도 HP는 돌아오지 않고, 다시 공감해도 또 줄지 않는다(US3-AC6)
  await liked.click();
  await expect(
    attacker.page.getByRole("button", { name: "공감 0" }),
  ).toHaveAttribute("aria-pressed", "false");
  await attacker.page.getByRole("button", { name: "공감 0" }).click();
  await expect(liked).toHaveAttribute("aria-pressed", "true");
  await expect(liked).toBeEnabled();
  await attacker.page.reload();
  await expect(hpText(attacker.page)).toHaveText("HP 9/10");
});

test("US3-AC2 다른 회원의 글에 댓글을 달면 댓글이 보이고 댓글 수가 늘며, 첫 댓글만 HP를 3 줄인다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const postId = await createAnalysedPost(author.page, "댓글 받을 글");
  const attacker = await newMember(browser, "b");

  await attacker.page.goto(`/post/${postId}`);
  await expect(hpText(attacker.page)).toHaveText("HP 10/10");
  await expect(counts(attacker.page).getByText("댓글 0")).toBeVisible();

  await writeCommentInUi(attacker.page, "힘내요");
  await expect(hpText(attacker.page)).toHaveText("HP 7/10");
  await expect(counts(attacker.page).getByText("댓글 1")).toBeVisible();

  // 두 번째 댓글부터는 HP가 줄지 않는다
  await writeCommentInUi(attacker.page, "정말 힘내요");
  await expect(counts(attacker.page).getByText("댓글 2")).toBeVisible();
  await attacker.page.reload();
  await expect(hpText(attacker.page)).toHaveText("HP 7/10");
  await expect(
    attacker.page.getByRole("article", {
      name: `${attacker.nickname}의 댓글`,
    }),
  ).toHaveCount(2);
});

test("US3-AC3 원 댓글에 답글을 달면 그 아래에 보인다", async ({ browser }) => {
  const author = await newMember(browser, "a");
  const postId = await createAnalysedPost(author.page, "답글 받을 글");
  const attacker = await newMember(browser, "b");
  await createComment(author.page, postId, "작성자의 댓글");

  await attacker.page.goto(`/post/${postId}`);
  const root = attacker.page.getByRole("article", {
    name: `${author.nickname}의 댓글`,
  });
  await root.getByRole("button", { name: "답글 달기" }).click();
  await expect(
    attacker.page.getByText(`${author.nickname}님에게 답글`),
  ).toBeVisible();
  await attacker.page.getByLabel("답글", { exact: true }).fill("저도 그래요");
  await attacker.page.getByRole("button", { name: "등록" }).click();

  const replies = attacker.page.getByRole("list", { name: "답글 목록" });
  await expect(
    replies.getByRole("article", { name: `${attacker.nickname}의 답글` }),
  ).toContainText("저도 그래요");
  // 답글도 이 회원의 첫 댓글이면 HP가 3 준다
  await expect(hpText(attacker.page)).toHaveText("HP 7/10");
});

test("US3-AC5 공격으로 HP가 0이 되면 처치된 모습으로 바뀌고, 그 뒤에도 공감과 댓글은 남지만 HP는 0에 머문다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const postId = await createAnalysedPost(author.page, "처치될 글");
  const attacker = await newMember(browser, "b");

  // 공감 −1, 첫 댓글 −3, 내 댓글 5개에 공감 −5: HP 1이 남는다
  expect(
    (await call(attacker.page, "POST", `/api/posts/${postId}/likes`)).status(),
  ).toBe(200);
  const comments: number[] = [];
  for (let i = 0; i < 6; i += 1) {
    comments.push(await createComment(attacker.page, postId, `응원 ${i}`));
  }
  for (const commentId of comments.slice(0, 5)) {
    expect(
      (
        await call(attacker.page, "POST", `/api/comments/${commentId}/likes`)
      ).status(),
    ).toBe(200);
  }

  await attacker.page.goto(`/post/${postId}`);
  await expect(hpText(attacker.page)).toHaveText("HP 1/10");
  await expect(monster(attacker.page).getByText("처치됨")).toHaveCount(0);

  // 마지막 한 번은 화면에서 댓글 공감으로 한다
  const last = commentArticle(attacker.page, "응원 5");
  await last.getByRole("button", { name: "댓글 공감 0" }).click();
  await expect(hpText(attacker.page)).toHaveText("HP 0/10");
  await expect(monster(attacker.page).getByText("처치됨")).toBeVisible();
  await expect(last.getByRole("button", { name: "댓글 공감 1" })).toBeEnabled();

  // 처치된 뒤에도 댓글은 남길 수 있고 HP는 0에 머문다
  await writeCommentInUi(attacker.page, "처치 뒤 응원");
  await attacker.page.reload();
  await expect(hpText(attacker.page)).toHaveText("HP 0/10");
  await expect(monster(attacker.page).getByText("처치됨")).toBeVisible();
  await expect(commentArticle(attacker.page, "처치 뒤 응원")).toBeVisible();
});

test("US3-AC8 내 글에는 공감 버튼이 없고, 댓글과 댓글 공감을 해도 HP가 줄지 않는다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const postId = await createAnalysedPost(author.page, "내 글");
  const visitor = await newMember(browser, "b");
  await createComment(visitor.page, postId, "다른 회원의 댓글");
  // 다른 회원의 첫 댓글로 HP 7이 된 상태에서 시작한다
  await author.page.goto(`/post/${postId}`);
  await expect(hpText(author.page)).toHaveText("HP 7/10");

  await expect(postLikeButton(author.page)).toHaveCount(0);
  await expect(
    counts(author.page).getByText("공감 0", { exact: true }),
  ).toBeVisible();
  const ownLike = await call(author.page, "POST", `/api/posts/${postId}/likes`);
  expect(ownLike.status()).toBe(403);

  await writeCommentInUi(author.page, "작성자의 댓글");
  await author.page
    .getByRole("article", { name: `${visitor.nickname}의 댓글` })
    .getByRole("button", { name: "댓글 공감 0" })
    .click();
  await expect(
    author.page.getByRole("button", { name: "댓글 공감 1" }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(hpText(author.page)).toHaveText("HP 7/10");

  await expect(
    author.page.getByRole("button", { name: "댓글 공감 1" }),
  ).toBeEnabled();
  await author.page.reload();
  await expect(hpText(author.page)).toHaveText("HP 7/10");
  await expect(commentArticle(author.page, "작성자의 댓글")).toBeVisible();
});

test("US3-AC10 공격이 성공하면 응답을 기다리지 않고 HP 표시가 바뀌고 몬스터가 맞는 반응을 한다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const postId = await createAnalysedPost(author.page, "맞을 글");
  const attacker = await newMember(browser, "b");

  // 맞는 반응(HP 바 흔들림)은 Web Animations API로 그린다. HP 바를 품은 요소의
  // 애니메이션만 센다(다른 요소의 애니메이션은 세지 않는다).
  await attacker.page.addInitScript(() => {
    const original = Element.prototype.animate;
    const counter = window as unknown as { __hits: number };
    counter.__hits = 0;
    Element.prototype.animate = function (
      this: Element,
      ...args: Parameters<Element["animate"]>
    ) {
      if (this.querySelector('[role="progressbar"][aria-label="몬스터 HP"]')) {
        counter.__hits += 1;
      }
      return original.apply(this, args);
    };
  });
  // 공감 응답을 늦춰, HP가 응답 전에 바뀌는지 본다
  let releaseLike: () => void = () => {};
  const likeHeld = new Promise<void>((resolve) => {
    releaseLike = resolve;
  });
  await attacker.page.route(`**/api/posts/${postId}/likes`, async (route) => {
    await likeHeld;
    await route.continue();
  });

  await attacker.page.goto(`/post/${postId}`);
  await expect(hpText(attacker.page)).toHaveText("HP 10/10");
  await attacker.page.getByRole("button", { name: "공감 0" }).click();

  await expect(hpText(attacker.page)).toHaveText("HP 9/10");
  await expect(
    monster(attacker.page).getByRole("progressbar", { name: "몬스터 HP" }),
  ).toHaveAttribute("aria-valuenow", "9");
  await expect
    .poll(() =>
      attacker.page.evaluate(
        () => (window as unknown as { __hits: number }).__hits,
      ),
    )
    .toBe(1);

  // 응답 뒤 상세를 다시 불러와도 HP는 그대로라 다시 흔들리지 않는다(두 번 흔들리면 실패).
  const refetched = attacker.page.waitForResponse(
    (response) =>
      response.request().method() === "GET" &&
      new URL(response.url()).pathname === `/api/posts/${postId}`,
  );
  releaseLike();
  await refetched;
  await expect(
    attacker.page.getByRole("button", { name: "공감 1" }),
  ).toBeEnabled();
  await expect(hpText(attacker.page)).toHaveText("HP 9/10");
  expect(
    await attacker.page.evaluate(
      () => (window as unknown as { __hits: number }).__hits,
    ),
  ).toBe(1);
  await attacker.page.reload();
  await expect(hpText(attacker.page)).toHaveText("HP 9/10");
});
