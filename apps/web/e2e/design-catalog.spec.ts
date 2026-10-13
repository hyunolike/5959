import { expect, test } from "@playwright/test";

for (const width of [320, 390, 1440]) {
  test(`DS-US2-AC4, DS-US3-AC6 카탈로그가 ${width}px에서 넘치지 않는다`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/debug/design");
    await expect(
      page.getByRole("heading", { level: 1, name: "디자인 카탈로그" }),
    ).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
    const layout = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > window.innerWidth,
      background: getComputedStyle(document.body).backgroundColor,
      font: getComputedStyle(document.body).fontFamily,
    }));
    expect(layout.overflow).toBe(false);
    expect(layout.background).toBe("rgb(255, 255, 255)");
    expect(layout.font).toContain("Pretendard");
  });
}

test("DS-US2-AC2 버튼의 누르는 영역이 44px 이상이다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/debug/design");
  for (const name of ["주요 동작", "테두리", "가라앉은 면", "글자만", "삭제"]) {
    const box = await page
      .getByRole("button", { name, exact: true })
      .boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(44);
  }
  const attack = await page
    .getByRole("button", { name: "공격하기", exact: true })
    .first()
    .boundingBox();
  expect(attack!.height).toBeGreaterThanOrEqual(56);
  const hitArea = await page
    .getByRole("button", { name: "전체", exact: true })
    .evaluate((chip) => parseFloat(getComputedStyle(chip, "::before").height));
  expect(hitArea).toBeGreaterThanOrEqual(44);
});

test("DS-US1-AC3 감정마다 태그의 점과 HP 막대가 그 감정의 색이다", async ({
  page,
}) => {
  await page.goto("/debug/design");
  for (const [emotion, expected] of [
    ["ANXIETY", "rgb(139, 92, 246)"],
    ["LETHARGY", "rgb(113, 135, 224)"],
    ["LONELINESS", "rgb(53, 182, 211)"],
    ["SELF_DEPRECATION", "rgb(138, 154, 60)"],
    ["IRRITATION", "rgb(229, 72, 77)"],
  ]) {
    const row = page.getByTestId(`emotion-${emotion}`);
    const colors = await row.evaluate((element) => {
      const dot = element.querySelector("[data-emotion] > [aria-hidden]")!;
      const fill = element.querySelector('[role="progressbar"] > div')!;
      return [
        getComputedStyle(dot).backgroundColor,
        getComputedStyle(fill).backgroundColor,
      ];
    });
    expect(colors).toEqual([expected, expected]);
  }
});

test("DS-US1-AC3 계약에 없는 감정 값은 회색으로 읽힌다", async ({ page }) => {
  await page.goto("/debug/design");
  const fill = await page
    .getByTestId("emotion-unknown")
    .evaluate(
      (element) => getComputedStyle(element.firstElementChild!).backgroundColor,
    );
  expect(fill).toBe("rgb(107, 107, 118)");
});

test("DS-US2-AC4 대화상자와 토스트를 열어 볼 수 있다", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/debug/design");
  await page.getByRole("button", { name: "대화상자 열기" }).click();
  const dialog = page.getByRole("alertdialog", { name: "글을 지울까요?" });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "취소" }).click();
  await expect(dialog).toBeHidden();

  await page.getByRole("button", { name: "토스트 띄우기" }).click();
  const toast = page.getByText("새 댓글이 달렸어요");
  await expect(toast).toBeVisible();
  const border = await toast.evaluate(
    (element) =>
      getComputedStyle(element.closest("div.pointer-events-auto")!)
        .borderTopColor,
  );
  expect(border).toBe("rgb(138, 138, 149)");
});

test("DS-US2-AC4 나머지 공용 컴포넌트와 상태가 카탈로그에 있다", async ({
  page,
}) => {
  await page.goto("/debug/design");
  await expect(page.getByRole("button", { name: "작은 버튼" })).toBeVisible();
  await expect(page.getByLabel("잠긴 입력")).toBeDisabled();
  await expect(page.getByTestId("catalog-card")).toBeVisible();
  await expect(page.getByTestId("catalog-loading")).toBeVisible();
  const widths = await page
    .getByTestId("catalog-hp-edges")
    .evaluate((element) =>
      [...element.querySelectorAll('[role="progressbar"] > div')].map(
        (fill) => (fill as HTMLElement).style.width,
      ),
    );
  expect(widths).toEqual(["0%", "1%", "99%", "100%"]);
});

test("DS-US3-AC2 감정이 정해지지 않은 HP 막대도 색이 있다", async ({
  page,
}) => {
  await page.goto("/debug/design");
  const fill = await page
    .getByTestId("emotion-fallback")
    .evaluate(
      (element) => getComputedStyle(element.firstElementChild!).backgroundColor,
    );
  expect(fill).toBe("rgb(107, 107, 118)");
});

test("DS-US3-AC2 게임 버튼은 눌리면 그림자가 없고, 요청 중에는 색과 그림자를 유지한다", async ({
  page,
}) => {
  await page.goto("/debug/design");
  const pressed = page.getByRole("button", { name: "공감 4", exact: true });
  const busy = page.getByRole("button", { name: "공감 5", exact: true });
  const locked = page
    .getByRole("button", { name: "공격하기", exact: true })
    .last();
  await expect(pressed).not.toHaveCSS("box-shadow", /0px 4px 0px/);
  await expect(busy).toHaveCSS("background-color", "rgb(255, 214, 10)");
  await expect(busy).toHaveCSS("box-shadow", /0px 4px 0px/);
  await expect(locked).toHaveCSS("background-color", "rgb(245, 245, 247)");
  await expect(locked).not.toHaveCSS("box-shadow", /0px 4px 0px/);
});
