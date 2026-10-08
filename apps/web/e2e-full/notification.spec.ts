import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";

// infra/compose.e2e.yaml로 띄운 실제 API, DB, Redis를 상대로 확인한다. 브라우저는 티켓을 같은 출처
// BFF에서 받고, 스트림은 API 도메인(SSE_PUBLIC_ORIGIN)에 바로 붙는다(004 research R3).
// 글쓴이 A와 행동하는 B, C는 서로 다른 브라우저 컨텍스트(쿠키)를 쓰는 다른 회원이다.

const APP_ORIGIN = "http://localhost:3000";
/** 브라우저가 스트림에 바로 붙는 API 주소. playwright.full.config.ts의 웹 서버 설정과 같다. */
const SSE_ORIGIN =
  process.env.SSE_PUBLIC_ORIGIN ??
  process.env.API_ORIGIN ??
  "http://localhost:18080";
const STREAM_URL = `${SSE_ORIGIN}/api/v1/notifications/stream`;
const TICKET_PATH = "/api/notifications/stream-ticket";
const PASSWORD = "abcd1234";

interface Member {
  context: BrowserContext;
  page: Page;
  nickname: string;
  email: string;
}

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

function bell(page: Page): Locator {
  return page.getByRole("link", { name: /^알림/ });
}

/** 배지 숫자까지 포함한 종의 이름. 0이면 배지가 없다. */
function bellName(unread: number): string {
  return unread === 0 ? "알림" : `알림, 안 읽은 알림 ${unread}개`;
}

async function expectUnread(page: Page, unread: number) {
  await expect(bell(page)).toHaveAccessibleName(bellName(unread));
}

/** 실시간 연결이 열릴 때까지 기다린다. 열리기 전에 생긴 알림은 토스트로 오지 않는다. */
async function waitForStreamOpen(page: Page) {
  await expect(bell(page)).toHaveAttribute("data-stream-status", "open");
}

function toast(page: Page, message: string | RegExp): Locator {
  return page
    .getByRole("region", { name: "새 알림" })
    .getByRole("button", { name: message });
}

interface TrackedWindow {
  openedEventSources: EventSource[];
}

/**
 * 페이지가 여는 EventSource를 모아 둔다. Chromium의 오프라인 흉내(`setOffline`)는 새 요청만 막고
 * 이미 열린 스트림은 끊지 않아서, 테스트가 열린 스트림을 직접 끊을 수 있어야 한다.
 */
async function trackEventSources(context: BrowserContext) {
  await context.addInitScript(() => {
    const opened: EventSource[] = [];
    (window as unknown as TrackedWindow).openedEventSources = opened;
    const Native = window.EventSource;
    window.EventSource = class extends Native {
      constructor(url: string | URL, init?: EventSourceInit) {
        super(url, init);
        opened.push(this);
      }
    };
  });
}

/**
 * 열린 스트림의 연결을 끊고 브라우저가 끊김을 알릴 때와 같은 `error`를 낸다. 서버는 연결이 닫힌 것을
 * 보고, 웹은 새 티켓으로 다시 붙으려 한다.
 */
async function dropStreams(page: Page) {
  await page.evaluate(() => {
    for (const source of (window as unknown as TrackedWindow)
      .openedEventSources) {
      source.close();
      source.dispatchEvent(new Event("error"));
    }
  });
}

interface ToastLogWindow {
  toastLog: string[];
}

/**
 * 지금부터 뜨는 토스트의 문구를 모두 적어 둔다. 토스트는 4초 뒤 사라지므로, 몇 번 떴는지는 화면에
 * 남아 있는 것을 세지 않고 이 기록으로 센다.
 */
async function recordToasts(page: Page) {
  await page.evaluate(() => {
    const log: string[] = [];
    (window as unknown as ToastLogWindow).toastLog = log;
    const region = document.querySelector('section[aria-label="새 알림"]');
    if (region === null) {
      throw new Error("토스트 영역이 없다");
    }
    new MutationObserver((mutations) => {
      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          const message = (node as Element).querySelector?.("button");
          if (message?.textContent) {
            log.push(message.textContent);
          }
        }
      }
    }).observe(region, { childList: true });
  });
}

function recordedToasts(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    [...(window as unknown as ToastLogWindow).toastLog].sort(),
  );
}

/** 서버가 아는 안 읽은 수. 브라우저 밖(테스트 프로세스)에서 불러서 오프라인 흉내에 막히지 않는다. */
async function serverUnreadCount(member: Member): Promise<number> {
  const response = await member.page.request.get(
    "/api/notifications/unread-count",
  );
  expect(response.status()).toBe(200);
  return ((await response.json()) as { data: { count: number } }).data.count;
}

/** 새 컨텍스트에서 가입과 온보딩을 마치고, 홈에서 실시간 연결까지 열린 회원. */
async function newMember(
  browser: Browser,
  prefix: string,
  prepare?: (context: BrowserContext) => Promise<void>,
): Promise<Member> {
  const context = await browser.newContext();
  await prepare?.(context);
  const page = await context.newPage();
  const nickname = uniqueNickname(prefix);
  const email = uniqueEmail(`noti-${prefix}`);
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption("DESIGN");
  await page.getByLabel("경력").selectOption("YEAR_3");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
  await waitForStreamOpen(page);
  return { context, page, nickname, email };
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

/**
 * 바로 분석되는 글(불안, 최대 HP 10)을 쓰고, 글쓴이 화면에 "몬스터가 나타났어요"가 올 때까지
 * 기다린다. 그 뒤의 배지 숫자가 몬스터 알림과 섞이지 않게 여기서 한 번에 맞춘다(안 읽은 수 1).
 */
async function writePostAndWaitForMonster(
  author: Member,
  label: string,
): Promise<number> {
  const response = await call(author.page, "/api/posts", {
    content: `[불안:낮음] ${label} ${Date.now()}`,
    commentTone: "COMFORT_ME",
  });
  expect(response.status()).toBe(201);
  const postId = ((await response.json()) as { data: { postId: number } }).data
    .postId;
  await expect(toast(author.page, "몬스터가 나타났어요")).toBeVisible({
    timeout: 30_000,
  });
  await expectUnread(author.page, 1);
  return postId;
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

async function like(member: Member, postId: number) {
  const response = await call(member.page, `/api/posts/${postId}/likes`);
  expect(response.ok()).toBe(true);
}

/** 이 페이지가 스트림과 티켓 라우트로 보낸 요청을 모은다. */
function recordStreamRequests(page: Page): string[] {
  const requests: string[] = [];
  page.on("request", (request) => {
    const url = request.url();
    if (url.includes("/notifications/stream")) {
      requests.push(url);
    }
  });
  return requests;
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

function notificationItems(page: Page): Locator {
  return page.getByRole("list", { name: "알림" }).getByRole("listitem");
}

/**
 * 종을 눌러 알림 목록으로 간다. 화면 안 이동이라 실시간 연결은 열린 채로 있다. 목록이 그려질 때까지 기다린다.
 */
async function openNotificationList(page: Page) {
  await bell(page).click();
  await page.waitForURL("**/notifications");
  await expect(page.getByRole("heading", { name: "알림" })).toBeVisible();
  await expect(notificationItems(page).first()).toBeVisible();
}

async function closeAll(...members: Member[]) {
  await Promise.all(members.map((member) => member.context.close()));
}

test("US1-AC1 다른 회원이 내 글에 댓글과 답글을 달면 새로고침 없이 토스트가 뜨고 배지가 늘어난다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const visitor = await newMember(browser, "b");
  const postId = await writePostAndWaitForMonster(author, "댓글 받을 글");
  // 페이지를 새로 불러오면 사라지는 표시. 끝까지 남아 있으면 새로고침이 없었다는 뜻이다.
  await author.page.evaluate(() => {
    (window as unknown as { stayedOnPage: boolean }).stayedOnPage = true;
  });

  const commentBody = `본문은 토스트에 나오면 안 된다 ${Date.now()}`;
  const commentId = await comment(visitor, postId, commentBody);

  const commentToast = toast(
    author.page,
    `${visitor.nickname} 님이 내 글에 댓글을 남겼어요`,
  );
  await expect(commentToast).toBeVisible();
  await expectUnread(author.page, 2);
  // 토스트에는 종류 문구만 있고 댓글 본문은 없다(ADR-0005).
  await expect(
    author.page.getByRole("region", { name: "새 알림" }),
  ).not.toContainText("본문은 토스트에");

  expect(
    await author.page.evaluate(
      () => (window as unknown as { stayedOnPage?: boolean }).stayedOnPage,
    ),
  ).toBe(true);

  await comment(visitor, postId, "답글", commentId);

  // 토스트는 4초 뒤 사라진다. 뜨면 바로 누르고, 숫자는 남아 있는 배지로 확인한다.
  await toast(
    author.page,
    `${visitor.nickname} 님이 내 글에 답글을 남겼어요`,
  ).click();
  await author.page.waitForURL(`**/post/${postId}`);
  // 누른 답글 알림은 읽음이 된다(US2-AC3). 몬스터와 댓글 알림 둘이 안 읽은 채 남는다.
  await expectUnread(author.page, 2);

  await closeAll(author, visitor);
});

test("US1-AC2 같은 글의 공감은 하나로 묶여 '닉네임 님 외 N명이 공감했어요'로 온다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const first = await newMember(browser, "b");
  const second = await newMember(browser, "c");
  const postId = await writePostAndWaitForMonster(author, "공감 받을 글");

  await like(first, postId);

  await expect(
    toast(author.page, `${first.nickname} 님이 공감했어요`),
  ).toBeVisible();
  await expectUnread(author.page, 2);

  await like(second, postId);

  await expect(
    toast(author.page, `${second.nickname} 님 외 1명이 공감했어요`),
  ).toBeVisible();
  // 새 알림이 아니라 같은 묶음이 갱신된 것이라 안 읽은 수는 그대로다.
  await expectUnread(author.page, 2);

  await closeAll(author, first, second);
});

test("US1-AC3 내 글의 감정 분석이 끝나면 '몬스터가 나타났어요' 알림이 온다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  await expectUnread(author.page, 0);

  // 글을 쓰고 홈에 그대로 있는다. 알림은 새로고침 없이 온다.
  await writePostAndWaitForMonster(author, "몬스터가 생길 글");

  await expect(author.page).toHaveURL(/\/home$/);

  await closeAll(author);
});

test("US1-AC6 연결이 끊긴 동안 생긴 댓글 2개와 공감이 다시 붙은 뒤 한 번씩 온다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a", trackEventSources);
  const visitor = await newMember(browser, "b");
  const postId = await writePostAndWaitForMonster(author, "끊긴 동안 받을 글");

  // 네트워크를 끊고 열린 스트림도 끊는다. 새 티켓을 받지 못하므로 다시 붙지 못하고 기다린다.
  await author.context.setOffline(true);
  await dropStreams(author.page);
  await expect(bell(author.page)).not.toHaveAttribute(
    "data-stream-status",
    "open",
  );
  await recordToasts(author.page);

  await comment(visitor, postId, "끊긴 동안 첫 댓글");
  await comment(visitor, postId, "끊긴 동안 둘째 댓글");
  await like(visitor, postId);

  // 서버에는 알림 세 개가 더 생겼지만(몬스터 1 + 3), 끊긴 화면에는 아무것도 오지 않았다.
  await expect.poll(() => serverUnreadCount(author)).toBe(4);
  await expectUnread(author.page, 1);
  await expect(bell(author.page)).not.toHaveAttribute(
    "data-stream-status",
    "open",
  );
  expect(await recordedToasts(author.page)).toEqual([]);

  await author.context.setOffline(false);

  await waitForStreamOpen(author.page);
  await expectUnread(author.page, 4);
  // 끊긴 동안의 알림이 빠짐없이, 한 번씩만 왔다.
  const commentMessage = `${visitor.nickname} 님이 내 글에 댓글을 남겼어요`;
  await expect
    .poll(() => recordedToasts(author.page))
    .toEqual(
      [
        commentMessage,
        commentMessage,
        `${visitor.nickname} 님이 공감했어요`,
      ].sort(),
    );

  await closeAll(author, visitor);
});

test("열린 스트림에 서버의 ping 이벤트가 오고, 그동안 연결은 열린 채로 있다", async ({
  browser,
}) => {
  // 서버 하트비트는 25초마다 온다.
  test.setTimeout(60_000);
  const member = await newMember(browser, "a", trackEventSources);

  // 웹은 이 이벤트로 조용히 죽은 연결을 가려낸다. 브라우저가 실제로 받는지 본다.
  const ping = await member.page.evaluate(
    () =>
      new Promise<{ data: string; lastEventId: string }>((resolve) => {
        const [source] = (window as unknown as TrackedWindow)
          .openedEventSources;
        source.addEventListener("ping", (event) => {
          const { data, lastEventId } = event as MessageEvent<string>;
          resolve({ data, lastEventId });
        });
      }),
  );

  // id가 없어 마지막 이벤트 id를 바꾸지 않는다.
  expect(ping).toEqual({ data: "{}", lastEventId: "" });
  await expect(bell(member.page)).toHaveAttribute("data-stream-status", "open");

  await closeAll(member);
});

test("US1-AC7 같은 회원의 탭 두 개 모두에 알림이 온다", async ({ browser }) => {
  const author = await newMember(browser, "a");
  const visitor = await newMember(browser, "b");
  const secondTab = await author.context.newPage();
  await secondTab.goto("/home");
  await waitForHomeLoaded(secondTab);
  await waitForStreamOpen(secondTab);
  const postId = await writePostAndWaitForMonster(author, "두 탭이 받을 글");
  await expectUnread(secondTab, 1);

  await comment(visitor, postId, "두 탭에 가는 댓글");

  const message = `${visitor.nickname} 님이 내 글에 댓글을 남겼어요`;
  await expect(toast(author.page, message)).toBeVisible();
  await expect(toast(secondTab, message)).toBeVisible();
  await expectUnread(author.page, 2);
  await expectUnread(secondTab, 2);

  await closeAll(author, visitor);
});

test("US1-AC8 로그인하지 않으면 알림 종이 없고 스트림 요청이 나가지 않는다", async ({
  browser,
}) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  const requests = recordStreamRequests(page);

  await page.goto("/login");
  await expect(page.getByRole("button", { name: "로그인" })).toBeVisible();
  await expect(bell(page)).toHaveCount(0);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "오구오구" })).toBeVisible();
  await expect(bell(page)).toHaveCount(0);
  expect(requests).toEqual([]);

  // 직접 불러도 티켓을 받지 못하고, 티켓 없이는 스트림에 붙지 못한다.
  const ticket = await page.request.fetch(TICKET_PATH, {
    method: "POST",
    headers: { Origin: APP_ORIGIN },
  });
  expect(ticket.status()).toBe(401);
  const stream = await page.request.get(
    `${STREAM_URL}?ticket=not-a-real-ticket&lastEventId=0`,
  );
  expect(stream.status()).toBe(401);

  await context.close();
});

test("US1-AC8 로그아웃하면 알림 종이 사라지고 연결을 닫으며, 다시 로그인하면 다시 붙는다", async ({
  browser,
}) => {
  const member = await newMember(browser, "a");
  const { page } = member;

  // 스트림은 이미 열려 있다. 로그아웃을 누른 순간부터 나가는 요청을 모은다.
  const afterLogout = recordStreamRequests(page);
  await page.getByRole("button", { name: "로그아웃" }).click();
  await page.waitForURL("**/login");
  await expect(page.getByRole("button", { name: "로그인" })).toBeVisible();
  await expect(bell(page)).toHaveCount(0);

  // 범용 프록시로는 티켓을 받을 수 없다(전용 라우트만 발급한다).
  const viaProxy = await call(page, "/api/notifications/stream-tickets");
  expect(viaProxy.status()).toBe(404);

  await page.getByLabel("이메일").fill(member.email);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  // 로그아웃한 뒤 로그인하기 전까지는 티켓 요청도 스트림 요청도 없었다.
  expect(afterLogout).toEqual([]);
  await page.getByRole("button", { name: "로그인" }).click();
  await page.waitForURL("**/home");

  await waitForHomeLoaded(page);
  await waitForStreamOpen(page);
  // 주소에는 티켓과 마지막 번호만 실린다. access 토큰은 없다(FR-006).
  const streamRequest = afterLogout.find((url) => url.startsWith(STREAM_URL));
  expect(streamRequest).toBeDefined();
  expect(
    [...new URL(streamRequest ?? STREAM_URL).searchParams.keys()].sort(),
  ).toEqual(["lastEventId", "ticket"]);

  await closeAll(member);
});

test("US2-AC1 알림 목록은 최신 알림부터 보이고 항목마다 문구, 내 글 앞부분, 시각, 읽음 여부가 있다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const commenter = await newMember(browser, "b");
  const liker = await newMember(browser, "c");
  const postId = await writePostAndWaitForMonster(author, "목록에 보일 글");
  const commentBody = `목록에 나오면 안 되는 댓글 본문 ${Date.now()}`;
  await comment(commenter, postId, commentBody);
  await expectUnread(author.page, 2);
  await like(liker, postId);
  await expectUnread(author.page, 3);

  await openNotificationList(author.page);

  const items = notificationItems(author.page);
  await expect(items).toHaveCount(3);
  // 최신순: 공감, 댓글, 몬스터.
  await expect(items.nth(0)).toContainText(`${liker.nickname} 님이 공감했어요`);
  await expect(items.nth(1)).toContainText(
    `${commenter.nickname} 님이 내 글에 댓글을 남겼어요`,
  );
  await expect(items.nth(2)).toContainText("몬스터가 나타났어요");
  for (const item of await items.all()) {
    await expect(item).toContainText("목록에 보일 글");
    await expect(item).toContainText("안 읽음");
    await expect(item.locator("time")).toHaveAttribute(
      "datetime",
      /^\d{4}-\d{2}-\d{2}T/,
    );
    await expect(item.getByRole("link")).toHaveAttribute(
      "href",
      `/post/${postId}`,
    );
  }
  // 남의 댓글 본문은 목록에 싣지 않는다(ADR-0005).
  await expect(author.page.getByRole("main")).not.toContainText(
    "목록에 나오면 안 되는",
  );
  // 목록을 여는 것만으로는 읽음이 되지 않는다.
  await expectUnread(author.page, 3);

  await closeAll(author, commenter, liker);
});

test("US2-AC2 목록 끝까지 내리면 다음 20개가 이어 붙고 같은 알림이 두 번 나오지 않는다", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  const author = await newMember(browser, "a");
  const commenter = await newMember(browser, "b");
  const postId = await writePostAndWaitForMonster(author, "알림이 많은 글");
  for (let index = 0; index < 24; index += 1) {
    await comment(commenter, postId, `댓글 ${index}`);
  }
  // 댓글로 몬스터가 처치되면 처치 알림도 온다. 모두 안 읽음이므로 서버의 안 읽은 수가 알림 전체 수다.
  await expect.poll(() => serverUnreadCount(author)).toBeGreaterThanOrEqual(25);
  const total = await serverUnreadCount(author);
  await expectUnread(author.page, total);

  await openNotificationList(author.page);

  const items = notificationItems(author.page);
  await expect(items).toHaveCount(20);

  await items.last().scrollIntoViewIfNeeded();

  // 다 불러오면 서버가 아는 수와 같다. 중복이 있으면 더 많고, 빠진 것이 있으면 더 적다.
  await expect(items).toHaveCount(total);
  await expect(
    author.page.getByRole("button", { name: "더 보기" }),
  ).toHaveCount(0);
  await expect(items.last()).toContainText("몬스터가 나타났어요");

  await closeAll(author, commenter);
});

test("US2-AC3 안 읽은 알림을 누르면 글 상세로 이동하고 읽음이 되며 배지가 하나 준다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const commenter = await newMember(browser, "b");
  const postId = await writePostAndWaitForMonster(author, "눌러서 갈 글");
  await comment(commenter, postId, "읽을 댓글");
  await expectUnread(author.page, 2);
  await openNotificationList(author.page);
  const commentItem = notificationItems(author.page).filter({
    hasText: "댓글을 남겼어요",
  });
  await expect(commentItem).toContainText("안 읽음");

  await commentItem.getByRole("link").click();

  await author.page.waitForURL(`**/post/${postId}`);
  await expect(author.page.getByText("읽을 댓글")).toBeVisible();
  await expectUnread(author.page, 1);
  // 서버에도 읽음으로 남았다.
  await expect.poll(() => serverUnreadCount(author)).toBe(1);

  await openNotificationList(author.page);

  await expect(commentItem).toContainText("읽음");
  await expect(commentItem).not.toContainText("안 읽음");
  await expect(
    notificationItems(author.page).filter({ hasText: "몬스터가 나타났어요" }),
  ).toContainText("안 읽음");
  await expectUnread(author.page, 1);

  await closeAll(author, commenter);
});

test("US2-AC4 모두 읽음을 누르면 배지가 사라지고, 그 뒤에 온 알림은 안 읽은 채로 맨 위에 보인다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const commenter = await newMember(browser, "b");
  const liker = await newMember(browser, "c");
  const postId = await writePostAndWaitForMonster(author, "모두 읽을 글");
  await comment(commenter, postId, "첫 댓글");
  await like(liker, postId);
  await expectUnread(author.page, 3);
  await openNotificationList(author.page);
  const items = notificationItems(author.page);
  await expect(items).toHaveCount(3);

  await author.page.getByRole("button", { name: "모두 읽음" }).click();

  await expectUnread(author.page, 0);
  await expect(items.filter({ hasText: "안 읽음" })).toHaveCount(0);
  await expect(author.page.getByRole("status")).toHaveText(
    "모든 알림을 읽음으로 표시했어요.",
  );
  await expect.poll(() => serverUnreadCount(author)).toBe(0);

  // 모두 읽음 뒤에 온 알림은 새로고침 없이 맨 위에 안 읽음으로 나타난다. 목록을 보고 있으므로 토스트는 없다.
  await comment(commenter, postId, "모두 읽음 뒤의 댓글");

  await expect(items).toHaveCount(4);
  await expect(items.first()).toContainText("댓글을 남겼어요");
  await expect(items.first()).toContainText("안 읽음");
  await expect(items.filter({ hasText: "안 읽음" })).toHaveCount(1);
  await expectUnread(author.page, 1);

  // 새로 불러와도 서버 값이 같다.
  await author.page.reload();
  await expect(items).toHaveCount(4);
  await expect(items.filter({ hasText: "안 읽음" })).toHaveCount(1);
  await expectUnread(author.page, 1);

  await closeAll(author, commenter, liker);
});

test("US2-AC5 지운 글의 알림은 목록에 '삭제된 글'로 남고, 누르면 이동하지 않고 안내를 받는다", async ({
  browser,
}) => {
  const author = await newMember(browser, "a");
  const commenter = await newMember(browser, "b");
  const postId = await writePostAndWaitForMonster(author, "곧 지울 글");
  await comment(commenter, postId, "지워질 글의 댓글");
  await expectUnread(author.page, 2);
  await deletePost(author, postId);

  await openNotificationList(author.page);

  const items = notificationItems(author.page);
  await expect(items).toHaveCount(2);
  const commentItem = items.filter({ hasText: "댓글을 남겼어요" });
  await expect(commentItem).toContainText("삭제된 글");
  await expect(author.page.getByRole("main")).not.toContainText("곧 지울 글");
  // 갈 글이 없으므로 링크가 아니다.
  await expect(commentItem.getByRole("link")).toHaveCount(0);

  await commentItem.getByRole("button").click();

  await expect(author.page.getByRole("status")).toHaveText("삭제된 글이에요.");
  await expect(author.page).toHaveURL(/\/notifications$/);
  // 목록에는 남고, 본 것이므로 읽음이 된다.
  await expect(items).toHaveCount(2);
  await expect(commentItem).toContainText("삭제된 글");
  await expect(commentItem).not.toContainText("안 읽음");
  await expectUnread(author.page, 1);

  await closeAll(author, commenter);
});

test("로그인하지 않고 알림 목록을 열면 로그인 화면으로 간다(FR-014)", async ({
  browser,
}) => {
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto("/notifications");

  await page.waitForURL(/\/login\?next=%2Fnotifications$/);
  await expect(page.getByRole("button", { name: "로그인" })).toBeVisible();

  await context.close();
});
