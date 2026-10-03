import { expect, type Page } from "@playwright/test";

/**
 * 홈의 두 조회(내 프로필, 피드)가 끝날 때까지 기다린다. 끝나기 전에 쿠키를 지우면 늦게 나간
 * 조회가 401을 받아 로그인 화면으로 튕기고, 다음 page.goto가 ERR_ABORTED로 끊긴다.
 */
export async function waitForHomeLoaded(page: Page) {
  await expect(page.getByRole("heading")).toContainText("님, 반가워요");
  const feed = page.getByRole("region", { name: "피드" });
  await expect(
    feed.getByRole("list").or(feed.getByText(/고민이 없어요/)),
  ).toBeVisible();
}
