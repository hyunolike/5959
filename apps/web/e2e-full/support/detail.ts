import type { Locator, Page } from "@playwright/test";

/**
 * 글 상세의 글 자체(본문, 몬스터, 공감과 댓글). 아래 추천 구역(007)의 카드에도 몬스터와 HP가 있어,
 * 글의 것을 볼 때는 이 안에서 찾는다.
 */
export function postArticle(page: Page): Locator {
  return page.getByRole("article", { name: "고민 글" });
}
