import { expect, test, type Page } from "@playwright/test";
import {
  createRunner,
  jumpRunner,
  PLAYER_X,
  PLAYER_WIDTH,
  stepRunner,
  type RunnerState,
} from "../src/widgets/auth-welcome/model/wake-game";

async function sleepingApi(page: Page) {
  const state = { up: false, requests: [] as string[] };
  await page.setExtraHTTPHeaders({
    cookie: "__Host-ogu_rt=recovery-test; __Host-ogu_ob=1",
  });
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/health/live") {
      await route.fulfill({ json: { status: state.up ? "UP" : "DOWN" } });
      return;
    }
    state.requests.push(path);
    const data =
      path === "/api/members/me"
        ? {
            id: 1,
            nickname: "돌아온 회원",
            jobRole: "DEVELOPMENT",
            careerYear: "YEAR_1",
            onboarded: true,
          }
        : path === "/api/feed" || path === "/api/members/me/posts"
          ? { items: [], nextCursor: null }
          : path === "/api/raid"
            ? { boss: null, available: true, myDamage: 0, nextBossAt: null }
            : path === "/api/notifications/unread-count"
              ? { count: 0 }
              : null;
    await route.fulfill({
      status: data === null ? 503 : 200,
      json: {
        success: data !== null,
        data,
        error:
          data === null
            ? { code: "TEST_UNAVAILABLE", message: "검증용 응답" }
            : null,
      },
    });
  });
  return state;
}

async function openRunner(page: Page) {
  await page.getByRole("button", { name: "오구와 놀기" }).click();
  const game = page.getByRole("region", { name: "오구의 마음 산책" });
  await expect(
    game.getByRole("button", { name: "톡 눌러 함께 산책하기" }),
  ).toBeVisible();
  return game;
}

test("낮에서 밤으로 바뀌고 일시정지 후 이어서 다시 낮으로 돌아온다", async ({
  page,
}, testInfo) => {
  test.setTimeout(90_000);
  await page.setViewportSize({ width: 390, height: 844 });
  const start = new Date("2026-11-13T12:00:01Z");
  await page.clock.install({ time: new Date("2026-11-13T12:00:00Z") });
  await page.clock.pauseAt(start);
  await sleepingApi(page);
  await page.goto("/home");
  const game = await openRunner(page);
  const canvas = game.locator("canvas");
  const skyColor = () =>
    canvas.evaluate((element) => {
      if (!(element instanceof HTMLCanvasElement))
        throw new Error("산책 Canvas가 필요해요");
      const context = element.getContext("2d")!;
      return Array.from(
        context.getImageData(
          Math.floor(element.width / 2),
          Math.floor(element.height * 0.55),
          1,
          1,
        ).data,
      ).slice(0, 3);
    });
  expect(await skyColor()).toEqual([255, 255, 255]);
  await page.screenshot({
    path: testInfo.outputPath("runner-day-390.png"),
    fullPage: true,
  });
  await page.keyboard.press("Space");
  // 물리 모델은 점프 입력을 맞추는 드라이버로만 사용한다. 기대값은 실제 Canvas 픽셀과 UI 상태다.
  let driver: RunnerState = jumpRunner(createRunner(start.getTime()));
  const walk = async (seconds: number) => {
    for (let elapsed = 0; elapsed < seconds; elapsed += 0.05) {
      const next = driver.obstacles.find(
        (obstacle) => obstacle.x + obstacle.width > PLAYER_X,
      );
      if (
        next &&
        next.x < PLAYER_X + 34 &&
        next.x > PLAYER_X + PLAYER_WIDTH - 3 &&
        driver.y === 0
      ) {
        await page.keyboard.press("Space");
        driver = jumpRunner(driver);
      }
      driver = stepRunner(driver, 0.05);
      expect(driver.crashed).toBe(false);
      await page.clock.runFor(50);
    }
    await expect(game.getByRole("button", { name: "점프하기" })).toBeVisible();
  };
  await walk(25);
  expect(await skyColor()).toEqual([23, 23, 28]);
  await expect(game.getByRole("button", { name: "점프하기" })).toHaveAttribute(
    "data-night",
    "true",
  );
  await page.screenshot({
    path: testInfo.outputPath("runner-night-390.png"),
    fullPage: true,
  });
  await game.getByRole("button", { name: "산책 일시정지" }).click();
  await page.clock.runFor(5_000);
  expect(await skyColor()).toEqual([23, 23, 28]);
  await page.screenshot({
    path: testInfo.outputPath("runner-night-paused-390.png"),
    fullPage: true,
  });
  await game.getByRole("button", { name: "이어서 걷기" }).click();
  await walk(25);
  expect(await skyColor()).toEqual([255, 255, 255]);
  await expect(game.getByRole("button", { name: "점프하기" })).toHaveAttribute(
    "data-night",
    "false",
  );
});

for (const width of [320, 1440]) {
  test(`산책에서 점프, 충돌, 재시작과 일시정지를 거쳐 서버가 깨어나면 복귀한다 (${width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.clock.install({ time: new Date("2026-11-13T12:00:00Z") });
    await page.clock.pauseAt(new Date("2026-11-13T12:00:01Z"));
    const api = await sleepingApi(page);
    await page.goto("/home");
    const game = await openRunner(page);
    await expect(game.getByText("함께 0m")).toBeVisible();
    await page.keyboard.press("Space");
    await page.clock.runFor(200);
    await expect(game.getByRole("button", { name: "점프하기" })).toBeVisible();
    expect(api.requests).toEqual([]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: testInfo.outputPath(`runner-jump-${width}.png`),
      fullPage: true,
    });
    await page.clock.runFor(7_300);
    await page.keyboard.press("Space");
    await page.clock.runFor(200);
    await page.screenshot({
      path: testInfo.outputPath(`runner-playing-${width}.png`),
      fullPage: true,
    });
    await page.clock.runFor(900);
    await expect(game.getByRole("button", { name: "점프하기" })).toBeVisible();
    await page.clock.runFor(4_000);
    await expect(game.getByText("잠깐 쉬어 가요")).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath(`runner-rest-${width}.png`),
      fullPage: true,
    });
    await game.getByRole("button", { name: "다시 산책하기" }).click();
    await expect(game.getByText("함께 0m")).toBeVisible();
    await page.clock.runFor(1_000);
    await game.getByRole("button", { name: "산책 일시정지" }).click();
    const stopped = await game.getByText(/^함께 \d+m$/).textContent();
    await page.clock.runFor(2_000);
    await expect(game.getByText(stopped!, { exact: true })).toBeVisible();
    await game.getByRole("button", { name: "이어서 걷기" }).click();
    api.up = true;
    // 폴링은 응답을 받은 뒤 다음 타이머를 건다. 요청 시각을 가정하지 않고 완료 직전까지만 시계를 옮긴다.
    for (let elapsed = 0; elapsed < 6_000; elapsed += 250) {
      await page.clock.runFor(250);
      if (await game.getByText("준비됐어요").isVisible()) break;
    }
    await expect(game.getByText("준비됐어요")).toBeVisible();
    await expect(
      game.getByRole("button", { name: "산책 완료" }),
    ).toHaveAttribute("aria-disabled", "true");
    await page.screenshot({
      path: testInfo.outputPath(`runner-ready-${width}.png`),
      fullPage: true,
    });
    await page.clock.runFor(1_300);
    await page.clock.resume();
    await expect(page.getByText("돌아온 회원님, 반가워요")).toBeVisible();
    await expect(game).toHaveCount(0);
  });

  test(`로그인 유지 상태에서 5분 넘게 잠든 API를 기다리고 새로고침 없이 복구한다 (${width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.clock.install();
    const api = await sleepingApi(page);
    await page.goto("/home");
    await expect(page.getByText("서버를 깨우고 있어요")).toBeVisible();
    await page.clock.fastForward("05:30");
    await expect(page.getByText("서버를 깨우고 있어요")).toBeVisible();
    expect(api.requests).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`waking-${width}.png`) });
    api.up = true;
    await page.clock.runFor(5_100);
    await expect(page.getByText("돌아온 회원님, 반가워요")).toBeVisible();
    await expect(page.getByText("아직 올라온 고민이 없어요.")).toBeVisible();
    expect(api.requests).toContain("/api/feed");
    await expect(page.getByText("서버를 깨우고 있어요")).toHaveCount(0);
  });
}

test.describe("모바일 터치", () => {
  test.use({ hasTouch: true });
  test("로그인 대기 중 놀이와 움직임 줄이기를 사용해도 입력과 자동 제출을 보존한다", async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.clock.install({ time: new Date("2026-11-13T12:00:00Z") });
    await page.clock.pauseAt(new Date("2026-11-13T12:00:01Z"));
    let up = false;
    const submissions: unknown[] = [];
    await page.route("**/api/health/live", (route) =>
      route.fulfill({ json: { status: up ? "UP" : "DOWN" } }),
    );
    await page.route("**/api/auth/login", async (route) => {
      submissions.push(route.request().postDataJSON());
      await route.fulfill({
        status: 401,
        json: {
          success: false,
          data: null,
          error: {
            code: "INVALID_CREDENTIALS",
            message: "이메일과 비밀번호를 확인해 주세요.",
          },
        },
      });
    });
    await page.goto("/login");
    await page.getByRole("button", { name: "이메일로 로그인" }).click();
    await page
      .getByRole("textbox", { name: "이메일", exact: true })
      .fill("ogu@example.com");
    await page.getByLabel("비밀번호", { exact: true }).fill("secret-pass1");
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "서버가 깨면 로그인할게요" }),
    ).toBeDisabled();
    const game = await openRunner(page);
    await expect(game.getByText("함께 0m")).toBeVisible();
    await expect(game.getByText(/직접 시작하면 길이 움직여요/)).toBeVisible();
    await game.getByRole("button", { name: "톡 눌러 함께 산책하기" }).tap();
    await page.clock.runFor(500);
    await page.getByLabel("비밀번호", { exact: true }).focus();
    await expect(
      game.getByRole("button", { name: "이어서 산책하기" }),
    ).toBeVisible();
    const stopped = await game.getByText(/^함께 \d+m$/).textContent();
    await page.keyboard.press("ArrowUp");
    await page.clock.runFor(1_000);
    await expect(game.getByText(stopped!, { exact: true })).toBeVisible();
    expect(submissions).toEqual([]);
    await page.screenshot({
      path: testInfo.outputPath("login-runner-390.png"),
      fullPage: true,
    });
    up = true;
    await page.clock.runFor(5_100);
    await expect(page.getByRole("main").getByRole("alert")).toHaveText(
      "이메일과 비밀번호를 확인해 주세요.",
    );
    expect(submissions).toEqual([
      { email: "ogu@example.com", password: "secret-pass1" },
    ]);
    await expect(
      page.getByRole("textbox", { name: "이메일", exact: true }),
    ).toHaveValue("ogu@example.com");
    await page.clock.runFor(1_300);
    await expect(game).toHaveCount(0);
  });
});

test("7분 뒤 대기를 끝내고 다시 연결하기로 자동 복구한다", async ({ page }) => {
  await page.clock.install();
  const api = await sleepingApi(page);
  await page.goto("/home");
  await expect(page.getByText("서버를 깨우고 있어요")).toBeVisible();
  await page.getByRole("button", { name: "오구와 놀기" }).click();
  await page.clock.fastForward("07:01");
  await expect(page.getByText("서버가 아직 일어나지 않았어요")).toBeVisible();
  await expect(page.getByLabel("깨우는 중")).toHaveCount(0);
  await expect(page.getByText("연결을 다시 확인해 주세요")).toBeVisible();
  await expect(page.getByRole("button", { name: "산책 멈춤" })).toHaveAttribute(
    "aria-disabled",
    "true",
  );
  expect(api.requests).toEqual([]);
  api.up = true;
  await page.getByRole("button", { name: "다시 연결하기" }).click();
  await expect(page.getByText("돌아온 회원님, 반가워요")).toBeVisible();
});
