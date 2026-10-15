import { expect, test, type Page } from "@playwright/test";

import { postArticle } from "./support/detail";
import { waitForHomeLoaded } from "./support/home";

// 016 첫 글로 이어지는 안내. infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로
// 가입 → 온보딩 → 환영 안내 → 첫 글 → 첫 글 안내까지 한 번에 확인한다.

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

async function signupAndOnboard(page: Page) {
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(uniqueEmail("first-post"));
  await page.getByLabel("비밀번호").fill("abcd1234");
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");

  // US2-AC1: 직군과 경력을 왜 묻는지 먼저 말한다.
  await expect(page.getByText(/이름과 회사는 묻지 않아요/)).toBeVisible();
  await page.getByLabel("닉네임").fill(uniqueNickname("f"));
  await page.getByLabel("직군").selectOption("DEVELOPMENT");
  await page.getByLabel("경력").selectOption("YEAR_1");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
}

test("US2-AC2 US1-AC4 US3-AC1 US3-AC4 US2-AC7 가입하면 환영 안내에서 첫 글로 이어지고 첫 글 안내가 한 번 보인다", async ({
  page,
}) => {
  await signupAndOnboard(page);

  // US2-AC2: 온보딩 직후의 홈에는 환영 안내가 있다.
  const write = page.getByRole("link", { name: "털어놓기", exact: true });
  await expect(write).toBeVisible();
  await write.click();
  await page.waitForURL("**/write");

  // US1-AC1: 공개 범위는 한 묶음이다. US1-AC4: 실마리를 눌러 이어 쓴다.
  await expect(
    page.getByRole("region", { name: "이 글은 이렇게 보여요" }),
  ).toBeVisible();
  await page
    .getByRole("group", { name: "쓰기 실마리" })
    .getByRole("button", { name: "오늘 있었던 일인데" })
    .click();
  const textarea = page.getByRole("textbox", { name: "고민", exact: true });
  await expect(textarea).toBeFocused();
  await page.keyboard.type("[불안:낮음] 발표에서 말이 막혔어요");
  await page.getByRole("button", { name: "무조건 위로해주기" }).click();
  await expect(page.getByText(/이런 댓글이 달려요/)).toBeVisible();
  await page.getByRole("button", { name: "올리기" }).click();

  // US3-AC1: 첫 글 안내가 보이고, 주소의 첫 글 표시는 바로 지워진다(US3-AC4).
  await page.waitForURL(/\/post\/\d+$/);
  await expect(
    postArticle(page).getByText(
      "오늘 있었던 일인데 [불안:낮음] 발표에서 말이 막혔어요",
    ),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "첫 고민을 남겼어요" }),
  ).toBeVisible();

  // US3-AC4: 새로 고치면 첫 글 안내가 없다.
  await page.reload();
  await expect(postArticle(page)).toBeVisible();
  await expect(
    page.getByRole("region", { name: "첫 고민을 남겼어요" }),
  ).toHaveCount(0);

  // US2-AC7: 글이 생긴 뒤 홈에는 환영 안내도 첫 고민 카드도 없다.
  await page.goto("/home");
  await waitForHomeLoaded(page);
  await expect(
    page.getByRole("link", { name: "털어놓기", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("complementary", { name: "첫 고민 안내" }),
  ).toHaveCount(0);
});

test("US2-AC5 US2-AC6 환영 안내는 한 번만 보이고 다시 열면 첫 고민 카드가 보인다", async ({
  page,
}) => {
  await signupAndOnboard(page);
  await expect(
    page.getByRole("link", { name: "털어놓기", exact: true }),
  ).toBeVisible();

  await page.reload();
  await waitForHomeLoaded(page);
  await expect(
    page.getByRole("link", { name: "털어놓기", exact: true }),
  ).toHaveCount(0);
  // 피드가 비어 있으면 빈 화면에도 같은 이름의 링크가 있다. 첫 고민 카드 안에서 찾는다.
  await expect(
    page
      .getByRole("complementary", { name: "첫 고민 안내" })
      .getByRole("link", { name: "첫 고민 남기기" }),
  ).toBeVisible();
});
