import { expect, test, type Browser, type Page } from "@playwright/test";

import { postArticle } from "./support/detail";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다(010). 글은 바로 분석되는 머리말
// (`[불안:낮음]`: 불안, 최대 HP 10)로 쓴다. 회원 한 명의 공감과 첫 댓글은 HP를 4 줄이므로 세 명이면 처치된다.
// 회원마다 다른 브라우저 컨텍스트(쿠키)를 쓴다. 새 회원이라 도감에는 이 테스트의 카드만 있다.

const APP_ORIGIN = "http://localhost:3000";

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

/** 새 컨텍스트에서 가입과 온보딩을 마친 회원의 페이지. */
async function newMember(browser: Browser, prefix: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(uniqueEmail(`cards-${prefix}`));
  await page.getByLabel("비밀번호").fill("abcd1234");
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("닉네임").fill(uniqueNickname(prefix));
  await page.getByLabel("직군").selectOption("DESIGN");
  await page.getByLabel("경력").selectOption("YEAR_3");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  return page;
}

/** 지금 로그인한 회원으로 같은 출처 BFF를 부른다. */
async function post(page: Page, path: string, data?: unknown) {
  return page.request.fetch(path, {
    method: "POST",
    headers: { Origin: APP_ORIGIN },
    data,
  });
}

function cardButtons(page: Page) {
  return page.getByRole("list", { name: "모은 카드 목록" }).getByRole("button");
}

test("US1-AC1, US1-AC2 몬스터를 물리치면 글쓴이와 함께한 회원의 도감에 카드가 생긴다", async ({
  browser,
}) => {
  test.setTimeout(120_000);
  const author = await newMember(browser, "author");
  const helpers = [
    await newMember(browser, "ha"),
    await newMember(browser, "hb"),
    await newMember(browser, "hc"),
  ];

  const created = await post(author, "/api/posts", {
    content: `[불안:낮음] 도감에 남을 고민 ${Date.now()}`,
    commentTone: "COMFORT_ME",
  });
  expect(created.status()).toBe(201);
  const postId = ((await created.json()) as { data: { postId: number } }).data
    .postId;
  // 몬스터가 생긴 뒤에 쳐야 공격이 바로 반영된다.
  await author.goto(`/post/${postId}`);
  await expect(postArticle(author).getByText("HP 10/10")).toBeVisible({
    timeout: 30_000,
  });

  for (const helper of helpers) {
    expect((await post(helper, `/api/posts/${postId}/likes`)).status()).toBe(
      200,
    );
    expect(
      (
        await post(helper, `/api/posts/${postId}/comments`, {
          content: "함께 물리쳐요",
        })
      ).status(),
    ).toBe(201);
  }

  // 글쓴이의 도감: 내 몬스터
  await author.goto("/cards");
  await expect(cardButtons(author)).toHaveCount(1);
  await expect(cardButtons(author).first()).toContainText("불안");
  await expect(cardButtons(author).first()).toContainText("일반");
  await expect(cardButtons(author).first()).toContainText("내 몬스터");
  await expect(author.getByText("모두 1장")).toBeVisible();
  await cardButtons(author).first().click();
  const mine = author.getByRole("dialog", { name: /^불안 카드 No\.\d+$/ });
  await expect(mine.getByText("함께 물리친 회원")).toBeVisible();
  await expect(mine.getByText("3명")).toBeVisible();

  // 함께한 회원의 도감: 함께 물리침. 글의 본문은 카드에 없다(US2-AC5).
  const helper = helpers[0];
  await helper.goto("/cards");
  await expect(cardButtons(helper)).toHaveCount(1);
  await expect(cardButtons(helper).first()).toContainText("함께 물리침");
  await cardButtons(helper).first().click();
  const together = helper.getByRole("dialog", { name: /^불안 카드 No\.\d+$/ });
  await expect(together.getByText("내가 준 피해")).toBeVisible();
  await expect(together).not.toContainText("도감에 남을 고민");
  await expect(together.getByRole("link", { name: "글 보기" })).toHaveAttribute(
    "href",
    `/post/${postId}`,
  );
});

test("US1-AC6 카드가 없는 회원은 카드를 얻는 방법을 본다", async ({
  browser,
}) => {
  const member = await newMember(browser, "empty");

  await member.goto("/cards");

  await expect(member.getByText("아직 모은 카드가 없어요")).toBeVisible();
  // 메뉴에도 같은 이름의 링크가 있어 본문 안에서 찾는다.
  await expect(
    member.getByRole("main").getByRole("link", { name: "고민 쓰기" }),
  ).toBeVisible();
});

test("US1-AC8 로그인하지 않고 도감을 열면 로그인 화면으로 간다", async ({
  browser,
}) => {
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto("/cards");

  await page.waitForURL(/\/login/);
});
