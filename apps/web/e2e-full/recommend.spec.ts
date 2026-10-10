import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";

// infra/compose.e2e.yaml로 띄운 실제 API와 DB를 상대로 비슷한 고민 추천(007)을 확인한다.
// e2e 프로필의 가짜 임베더는 본문의 `[주제:이름]`이 같은 글을 가깝게, 다른 글을 멀게 둔다.
// 주제 이름을 테스트마다 새로 지어 다른 테스트의 글과 섞이지 않게 한다.

const APP_ORIGIN = "http://localhost:3000";
const PASSWORD = "abcd1234";
const CRISIS_TEXT = "요즘 너무 힘들어서 죽고 싶어요";

interface Member {
  context: BrowserContext;
  page: Page;
}

interface SimilarBody {
  data: { basis: string; items: { postId: number }[]; pending: boolean };
}

function unique(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/** 가짜 임베더가 읽는 주제 표지. 이름은 20자까지다. */
function topicOf(name: string): string {
  return `[주제:${name}${unique()}]`.slice(0, 24);
}

/** 새 컨텍스트에서 가입과 온보딩을 마친 회원. */
async function newMember(browser: Browser, prefix: string): Promise<Member> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(`rec-${prefix}-${unique()}@example.com`);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page
    .getByLabel("닉네임")
    .fill(`${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10));
  await page.getByLabel("직군").selectOption("DESIGN");
  await page.getByLabel("경력").selectOption("YEAR_3");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
  return { context, page };
}

/** 화면을 거치지 않고 글을 쓴다. */
async function writePost(author: Member, content: string): Promise<number> {
  const response = await author.page.request.fetch("/api/posts", {
    method: "POST",
    headers: { Origin: APP_ORIGIN },
    data: { content, commentTone: "COMFORT_ME" },
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { data: { postId: number } }).data.postId;
}

async function deletePost(author: Member, postId: number) {
  const response = await author.page.request.fetch(`/api/posts/${postId}`, {
    method: "DELETE",
    headers: { Origin: APP_ORIGIN },
  });
  expect(response.status()).toBe(204);
}

/** 글 [postId]의 추천을 [viewer]로 부른다. 임베딩은 커밋 뒤에 따로 만들어지므로 준비됐는지 볼 때 쓴다. */
async function similarOf(viewer: Member, postId: number) {
  const response = await viewer.page.request.get(
    `/api/posts/${postId}/similar`,
  );
  expect(response.status()).toBe(200);
  return ((await response.json()) as SimilarBody).data;
}

/** [postId]의 비슷한 고민이 [expected]와 같아질 때까지 기다린다(가까운 순서). */
async function waitForSimilar(
  viewer: Member,
  postId: number,
  expected: number[],
) {
  await expect
    .poll(async () => {
      const data = await similarOf(viewer, postId);
      return data.basis === "SIMILAR"
        ? data.items.map((item) => item.postId)
        : data.basis;
    })
    .toEqual(expected);
}

function similarSection(page: Page): Locator {
  return page.getByRole("region", { name: "비슷한 고민" });
}

async function closeAll(...members: Member[]) {
  await Promise.all(members.map((member) => member.context.close()));
}

test("US1-AC1 글 아래에 비슷한 고민이 가까운 순서로 보이고 내 글과 무관한 글은 없다", async ({
  browser,
}) => {
  const viewer = await newMember(browser, "v");
  const other = await newMember(browser, "o");
  const topic = topicOf("상사");
  const mine = await writePost(viewer, `${topic} 팀장님이 무서워요`);
  await writePost(viewer, `${topic} 팀장님 때문에 또 썼어요`);
  const far = await writePost(other, `${topic} [멀기:5] 부장님 눈치가 보여요`);
  const near = await writePost(other, `${topic} 팀장님 앞에서 말이 안 나와요`);
  await writePost(other, `${topicOf("연봉")} 연봉 협상이 걱정돼요`);
  await waitForSimilar(viewer, mine, [near, far]);

  await viewer.page.goto(`/post/${mine}`);

  const cards = similarSection(viewer.page).getByRole("link");
  await expect(cards).toHaveCount(2);
  await expect(cards.nth(0)).toContainText("팀장님 앞에서 말이 안 나와요");
  await expect(cards.nth(1)).toContainText("부장님 눈치가 보여요");
  // US1-AC3 내 글, US1-AC4 주제가 다른 글
  await expect(similarSection(viewer.page)).not.toContainText("또 썼어요");
  await expect(similarSection(viewer.page)).not.toContainText("연봉 협상");

  await closeAll(viewer, other);
});

test("US1-AC2 추천의 글을 누르면 그 글의 상세로 간다", async ({ browser }) => {
  const viewer = await newMember(browser, "v");
  const other = await newMember(browser, "o");
  const topic = topicOf("야근");
  const mine = await writePost(viewer, `${topic} 오늘도 야근이에요`);
  const theirs = await writePost(other, `${topic} 야근이 끝나지 않아요`);
  await waitForSimilar(viewer, mine, [theirs]);
  await viewer.page.goto(`/post/${mine}`);

  await similarSection(viewer.page).getByRole("link").click();

  await viewer.page.waitForURL(`**/post/${theirs}`);
  await expect(viewer.page.getByText("야근이 끝나지 않아요")).toBeVisible();

  await closeAll(viewer, other);
});

test("US1-AC7 글을 쓰고 상세로 가면 새로고침 없이 비슷한 고민이 나타난다", async ({
  browser,
}) => {
  const viewer = await newMember(browser, "v");
  const other = await newMember(browser, "o");
  const topic = topicOf("이직");
  const theirs = await writePost(other, `${topic} 이직을 해야 할지 모르겠어요`);
  await expect
    .poll(async () => (await similarOf(other, theirs)).pending)
    .toBe(false);

  await viewer.page.goto("/write");
  await viewer.page.getByLabel("고민").fill(`${topic} 이직 준비가 막막해요`);
  await viewer.page.getByRole("button", { name: "무조건 위로해주기" }).click();
  await viewer.page.getByRole("button", { name: "올리기" }).click();
  await viewer.page.waitForURL(/\/post\/\d+$/);

  await expect(similarSection(viewer.page)).toContainText(
    "이직을 해야 할지 모르겠어요",
    { timeout: 30_000 },
  );

  await closeAll(viewer, other);
});

test("US2-AC2 임베딩을 만들지 못한 글에는 같은 감정의 고민이 보인다", async ({
  browser,
}) => {
  const viewer = await newMember(browser, "v");
  const other = await newMember(browser, "o");
  await writePost(other, `[불안:높음] 발표가 걱정돼요 ${unique()}`);
  const mine = await writePost(
    viewer,
    `[불안:높음] [임베딩실패] 내일이 걱정돼요 ${unique()}`,
  );
  await expect
    .poll(async () => (await similarOf(viewer, mine)).basis)
    .toBe("SAME_EMOTION");

  await viewer.page.goto(`/post/${mine}`);

  const section = viewer.page.getByRole("region", { name: "같은 감정의 고민" });
  await expect(section.getByRole("link").first()).toBeVisible();
  await expect(similarSection(viewer.page)).toHaveCount(0);
  // 글 읽기는 그대로다
  await expect(viewer.page.getByText("내일이 걱정돼요")).toBeVisible();

  await closeAll(viewer, other);
});

test("US2-AC3 임베딩도 감정 분석 결과도 없으면 추천 구역이 없다", async ({
  browser,
}) => {
  const viewer = await newMember(browser, "v");
  const mine = await writePost(
    viewer,
    `[실패] [임베딩실패] 아무도 모를 고민 ${unique()}`,
  );

  await viewer.page.goto(`/post/${mine}`);

  await expect(viewer.page.getByText("아무도 모를 고민")).toBeVisible();
  await expect(
    viewer.page.getByRole("region", { name: "댓글 목록" }),
  ).toBeVisible();
  expect((await similarOf(viewer, mine)).basis).toBe("NONE");
  await expect(similarSection(viewer.page)).toHaveCount(0);
  await expect(
    viewer.page.getByRole("region", { name: "같은 감정의 고민" }),
  ).toHaveCount(0);

  await closeAll(viewer);
});

test("US3-AC1 추천에 나오던 글을 지우면 추천에서 빠진다", async ({
  browser,
}) => {
  const viewer = await newMember(browser, "v");
  const other = await newMember(browser, "o");
  const topic = topicOf("회의");
  const mine = await writePost(viewer, `${topic} 회의가 너무 많아요`);
  const kept = await writePost(other, `${topic} 회의만 하다 하루가 가요`);
  const removed = await writePost(other, `${topic} [멀기:5] 회의록이 밀렸어요`);
  await waitForSimilar(viewer, mine, [kept, removed]);

  await deletePost(other, removed);
  await viewer.page.goto(`/post/${mine}`);

  await expect(similarSection(viewer.page).getByRole("link")).toHaveCount(1);
  await expect(similarSection(viewer.page)).toContainText("하루가 가요");
  await expect(similarSection(viewer.page)).not.toContainText("회의록");

  await closeAll(viewer, other);
});

test("US3-AC2 위기로 숨겨진 글은 다른 회원의 추천에 나오지 않는다", async ({
  browser,
}) => {
  const viewer = await newMember(browser, "v");
  const other = await newMember(browser, "o");
  const topic = topicOf("번아웃");
  const mine = await writePost(viewer, `${topic} 요즘 지쳤어요`);
  const shown = await writePost(other, `${topic} 쉬어도 피곤해요`);
  const hidden = await writePost(other, `${topic} ${CRISIS_TEXT}`);
  await waitForSimilar(viewer, mine, [shown]);
  // 숨긴 글의 임베딩이 끝난 뒤에도 나오지 않는지 본다
  await expect
    .poll(async () => (await similarOf(other, hidden)).pending)
    .toBe(false);

  await viewer.page.goto(`/post/${mine}`);

  await expect(similarSection(viewer.page).getByRole("link")).toHaveCount(1);
  await expect(similarSection(viewer.page)).toContainText("쉬어도 피곤해요");
  await expect(similarSection(viewer.page)).not.toContainText("죽고 싶어요");

  await closeAll(viewer, other);
});
