import { expect, test } from "@playwright/test";
import { waitForHomeLoaded } from "./support/home";
import { openEmailLogin } from "./support/login";

// 모든 데이터/쓰기 대상은 격리된 E2E API다. 운영 회원이나 게시글을 사용하지 않는다.
for (const width of [320, 390, 1440]) {
  test(`UX-US2-AC2 실제 회원 흐름 렌더와 익명 의도 복귀 (${width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 844 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    const capture = async (name: string) => {
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth > innerWidth,
        ),
      ).toBe(false);
      await page.screenshot({
        path: testInfo.outputPath(`${name}-${width}.png`),
        fullPage: name !== "feed" && name !== "login-sheet",
      });
    };
    await page.goto("/login");
    await capture("login");
    await page.getByRole("link", { name: "먼저 둘러보기" }).click();
    await expect(page).toHaveURL(/\/home$/);
    await capture("guest-home");
    await page
      .getByRole("main")
      .getByRole("button", { name: "고민 쓰기", exact: true })
      .click();
    await expect(page.getByRole("dialog")).toBeVisible();
    await capture("login-sheet");
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "계속 둘러보기" })
      .click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.goto("/signup");
    await capture("signup");
    const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    const email = `ui-review-${suffix}@example.com`;
    const nickname = `검증${Math.random().toString(36).slice(2, 7)}`;
    await page
      .getByRole("textbox", { name: "이메일", exact: true })
      .fill(email);
    await page.getByLabel("비밀번호", { exact: true }).fill("abcd1234");
    await page.getByRole("button", { name: "가입하기", exact: true }).click();
    await expect(page).toHaveURL(/\/onboarding$/);
    await page.getByLabel("닉네임", { exact: true }).fill(nickname);
    await page.getByLabel("직군", { exact: true }).selectOption("DEVELOPMENT");
    await page.getByLabel("경력", { exact: true }).selectOption("YEAR_1");
    await capture("onboarding");
    await page.getByRole("button", { name: "완료", exact: true }).click();
    await expect(page).toHaveURL(/\/home$/);
    await waitForHomeLoaded(page);
    await capture("feed");
    await page.goto("/write");
    await expect(
      page.getByRole("textbox", { name: "고민", exact: true }),
    ).toBeVisible();
    await capture("write");
    await page
      .getByRole("textbox", { name: "고민", exact: true })
      .fill(
        "[UI 검증용] 발표를 앞두고 조금 긴장돼요. 오늘의 마음을 차분히 나눠보고 싶어요.",
      );
    await page
      .getByRole("button", { name: "무조건 위로해주기", exact: true })
      .click();
    await page.getByRole("button", { name: "올리기", exact: true }).click();
    await expect(page).toHaveURL(/\/post\/\d+$/);
    await expect(
      page
        .getByRole("article", { name: "고민 글", exact: true })
        .getByText(/\[UI 검증용\] 발표를 앞두고/),
    ).toBeVisible();
    await capture("post-detail");
    await page.goto("/my");
    await expect(
      page.getByText(`${nickname}님의 마음 기록`, { exact: true }),
    ).toBeVisible();
    await capture("my");
    await page.getByRole("link", { name: "프로필 수정", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "프로필 수정", exact: true }),
    ).toBeVisible();
    await capture("profile-settings");

    // 실제 로그인까지 이어질 때도 guest prompt의 /write 의도를 보존한다.
    await page.context().clearCookies();
    await page.goto("/home");
    await page
      .getByRole("main")
      .getByRole("button", { name: "고민 쓰기", exact: true })
      .click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "로그인하기", exact: true })
      .click();
    await expect(page).toHaveURL(/\/login\?next=%2Fwrite$/);
    await openEmailLogin(page);
    await page
      .getByRole("textbox", { name: "이메일", exact: true })
      .fill(email);
    await page.getByLabel("비밀번호", { exact: true }).fill("abcd1234");
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await expect(page).toHaveURL(/\/write$/);
    await expect(
      page.getByRole("textbox", { name: "고민", exact: true }),
    ).toBeVisible();
  });
}
