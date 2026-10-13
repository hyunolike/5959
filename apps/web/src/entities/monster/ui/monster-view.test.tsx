import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { MonsterView } from "../model/types";
import { MonsterDisplay } from "./monster-view";
import { HIT_REACTION_MS } from "./use-hit-reaction";

const ANXIETY_FULL: MonsterView = {
  emotion: "ANXIETY",
  hp: 10,
  maxHp: 10,
  status: "ALIVE",
};

/** 움직임 줄이기 설정을 흉내 낸다. */
function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: reduce && query === "(prefers-reduced-motion: reduce)",
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    })),
  );
}

function spriteSrc(img: HTMLElement): string {
  return decodeURIComponent(img.getAttribute("src") ?? "");
}

let animate: ReturnType<typeof vi.fn>;

beforeEach(() => {
  animate = vi.fn();
  HTMLElement.prototype.animate = animate as unknown as HTMLElement["animate"];
  stubReducedMotion(false);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("MonsterDisplay 그림 고르기", () => {
  it("US5-AC3 감정과 HP 단계에 맞는 그림을 쓴다", () => {
    const cases: [MonsterView, string, string][] = [
      [ANXIETY_FULL, "/monsters/anxiety-full.webp", "불안 몬스터, 멀쩡함"],
      [
        { emotion: "LETHARGY", hp: 13, maxHp: 20, status: "ALIVE" },
        "/monsters/lethargy-hurt.webp",
        "무기력 몬스터, 상처 입음",
      ],
      [
        { emotion: "LONELINESS", hp: 20, maxHp: 30, status: "ALIVE" },
        "/monsters/loneliness-hurt.webp",
        "외로움 몬스터, 상처 입음",
      ],
      [
        { emotion: "SELF_DEPRECATION", hp: 10, maxHp: 30, status: "ALIVE" },
        "/monsters/self_deprecation-weak.webp",
        "자기비하 몬스터, 약해짐",
      ],
      [
        { emotion: "IRRITATION", hp: 0, maxHp: 10, status: "DEFEATED" },
        "/monsters/irritation-defeated.webp",
        "짜증 몬스터, 쓰러짐",
      ],
    ];

    for (const [monster, file, name] of cases) {
      const view = render(
        <MonsterDisplay monster={monster} variant="detail" />,
      );
      const img = screen.getByRole("img", { name });
      expect(img.tagName).toBe("IMG");
      expect(spriteSrc(img)).toContain(file);
      view.unmount();
    }
  });

  it("레이드 보스는 보스 그림을 쓰고, 살아 있는 동안은 한 장으로 색만 뺀다", () => {
    const boss = { emotion: "LETHARGY", maxHp: 300, status: "ALIVE" } as const;
    const { rerender } = render(
      <MonsterDisplay monster={{ ...boss, hp: 300 }} variant="boss" />,
    );
    const full = screen.getByRole("img", { name: "무기력 보스, 멀쩡함" });
    expect(spriteSrc(full)).toContain("/monsters/boss-lethargy.webp");
    expect(full.className).not.toContain("saturate");

    rerender(<MonsterDisplay monster={{ ...boss, hp: 50 }} variant="boss" />);
    const weak = screen.getByRole("img", { name: "무기력 보스, 약해짐" });
    expect(spriteSrc(weak)).toContain("/monsters/boss-lethargy.webp");
    expect(weak.className).toContain("saturate");

    rerender(
      <MonsterDisplay
        monster={{ ...boss, hp: 0, status: "DEFEATED" }}
        variant="boss"
      />,
    );
    expect(
      spriteSrc(screen.getByRole("img", { name: "무기력 보스, 쓰러짐" })),
    ).toContain("/monsters/boss-lethargy-defeated.webp");
  });
});

describe("MonsterDisplay 대기 움직임", () => {
  it.each([
    ["ANXIETY", "monster-idle-tremble"],
    ["LETHARGY", "monster-idle-sag"],
    ["LONELINESS", "monster-idle-sway"],
    ["SELF_DEPRECATION", "monster-idle-nod"],
    ["IRRITATION", "monster-idle-twitch"],
  ] as const)("상세의 %s 몬스터는 %s로 움직인다", (emotion, className) => {
    render(
      <MonsterDisplay
        monster={{ ...ANXIETY_FULL, emotion }}
        variant="detail"
      />,
    );

    expect(screen.getByRole("img").className).toContain(className);
  });

  it("쓰러진 몬스터는 움직이지 않는다", () => {
    render(
      <MonsterDisplay
        monster={{ ...ANXIETY_FULL, hp: 0, status: "DEFEATED" }}
        variant="detail"
      />,
    );
    expect(screen.getByRole("img").className).not.toContain("monster-idle");
  });
});

describe("MonsterDisplay 감정 이름과 HP", () => {
  it("감정 이름, HP 숫자, HP 바를 보여 준다", () => {
    render(
      <MonsterDisplay
        monster={{ emotion: "IRRITATION", hp: 4, maxHp: 10, status: "ALIVE" }}
        variant="detail"
      />,
    );
    expect(screen.getByText("짜증", { exact: true })).toBeInTheDocument();
    expect(screen.getByText("HP 4/10")).toBeInTheDocument();
    expect(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "4");
  });

  it("US3-AC5 처치된 몬스터는 처치됨을 보여 준다", () => {
    const { rerender } = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );
    expect(screen.queryByText("처치됨")).not.toBeInTheDocument();

    rerender(
      <MonsterDisplay
        monster={{ ...ANXIETY_FULL, hp: 0, status: "DEFEATED" }}
        variant="detail"
      />,
    );
    expect(screen.getByText("처치됨")).toBeInTheDocument();
    expect(screen.getByText("HP 0/10")).toBeInTheDocument();
    expect(
      spriteSrc(screen.getByRole("img", { name: "불안 몬스터, 쓰러짐" })),
    ).toContain("/monsters/anxiety-defeated.webp");
  });
});

describe("MonsterDisplay 무대 모양", () => {
  it("DS-US3-AC2 큰 HP 막대와 그 감정의 색 범위를 쓴다", () => {
    const { container } = render(
      <MonsterDisplay
        monster={{ emotion: "LONELINESS", hp: 5, maxHp: 10, status: "ALIVE" }}
        variant="detail"
      />,
    );
    expect(container.firstElementChild).toHaveAttribute(
      "data-emotion",
      "LONELINESS",
    );
    expect(screen.getByRole("progressbar", { name: "몬스터 HP" })).toHaveClass(
      "h-[22px]",
    );
    expect(screen.getByText("상처 입음")).toBeInTheDocument();
    // 무대가 감정의 옅은 색이라 태그는 흰 면으로 구분한다.
    expect(screen.getByText("외로움")).toHaveClass("bg-surface");
  });

  it("처치된 몬스터의 HP 글자는 처치됨과 다른 요소다", () => {
    render(
      <MonsterDisplay
        monster={{ ...ANXIETY_FULL, hp: 0, status: "DEFEATED" }}
        variant="detail"
      />,
    );
    expect(screen.getByText(/^HP \d+\/\d+$/)).toHaveTextContent(/^HP 0\/10$/);
    expect(screen.getByText("처치됨")).toBeInTheDocument();
    expect(screen.queryByText("쓰러짐")).not.toBeInTheDocument();
  });
});

describe("MonsterDisplay HP 숫자", () => {
  function bossHpParagraph() {
    return screen.getByText(
      (_, element) =>
        element?.tagName === "P" &&
        /^HP [\d,]+\/[\d,]+$/.test(element.textContent ?? ""),
    );
  }

  it("DS-US3-AC3 보스의 HP 숫자는 36px 단계이고 네 자리부터 쉼표를 넣는다", () => {
    render(
      <MonsterDisplay
        monster={{
          emotion: "IRRITATION",
          hp: 4120,
          maxHp: 10000,
          status: "ALIVE",
        }}
        variant="boss"
      />,
    );
    // 숫자마다 크기가 달라 여러 요소로 나뉜다. 문단 전체의 글자가 "HP n/m"인지 본다.
    expect(bossHpParagraph()).toHaveTextContent(/^HP 4,120\/10,000$/);
    expect(screen.getByText("4,120")).toHaveClass("text-numeric");
  });

  it("006 US5-AC1 보스의 HP 막대는 보스 HP라는 이름과 HP 숫자로 읽힌다", () => {
    render(
      <MonsterDisplay
        monster={{ emotion: "LETHARGY", hp: 200, maxHp: 300, status: "ALIVE" }}
        variant="boss"
      />,
    );
    expect(
      screen.getByRole("progressbar", { name: "보스 HP" }),
    ).toHaveAttribute("aria-valuetext", "HP 200/300");
  });

  it("세 자리 이하 보스의 HP 글자는 그대로다", () => {
    render(
      <MonsterDisplay
        monster={{ emotion: "LETHARGY", hp: 30, maxHp: 30, status: "ALIVE" }}
        variant="boss"
      />,
    );
    expect(bossHpParagraph()).toHaveTextContent(/^HP 30\/30$/);
  });

  it("DS-US3-AC3 계약을 어긴 HP 값이 와도 화면이 죽지 않는다", () => {
    render(
      <MonsterDisplay
        monster={{
          emotion: "IRRITATION",
          hp: undefined as unknown as number,
          maxHp: Number.NaN,
          status: "ALIVE",
        }}
        variant="boss"
      />,
    );
    expect(bossHpParagraph()).toHaveTextContent(/^HP 0\/0$/);
  });

  it("상세의 HP 글자에는 쉼표를 넣지 않는다", () => {
    render(
      <MonsterDisplay
        monster={{ emotion: "ANXIETY", hp: 1200, maxHp: 3000, status: "ALIVE" }}
        variant="detail"
      />,
    );
    expect(screen.getByText("HP 1200/3000")).toBeInTheDocument();
  });
});

describe("MonsterDisplay 맞는 반응", () => {
  it("US3-AC10 HP가 줄면 그림과 HP 바를 함께 흔들고 바뀐 HP를 바로 보여 준다", () => {
    const { rerender } = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );
    expect(animate).not.toHaveBeenCalled();

    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );

    expect(screen.getByText("HP 9/10")).toBeInTheDocument();
    expect(animate).toHaveBeenCalledTimes(1);
    expect(animate.mock.calls[0][1]).toMatchObject({
      duration: HIT_REACTION_MS,
    });
    const shaken = animate.mock.contexts[0] as HTMLElement;
    expect(shaken).toContainElement(screen.getByRole("img"));
    expect(shaken).toContainElement(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    );
  });

  it("HP가 늘거나 그대로면(서버 값으로 맞출 때) 흔들지 않는다", () => {
    const { rerender } = render(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 7 }} variant="detail" />,
    );
    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );
    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );
    expect(animate).not.toHaveBeenCalled();
  });

  it("움직임 줄이기가 켜져 있으면 흔들지 않고 HP만 바꾼다", () => {
    stubReducedMotion(true);
    const { rerender } = render(
      <MonsterDisplay monster={ANXIETY_FULL} variant="detail" />,
    );
    rerender(
      <MonsterDisplay monster={{ ...ANXIETY_FULL, hp: 9 }} variant="detail" />,
    );

    expect(screen.getByText("HP 9/10")).toBeInTheDocument();
    expect(animate).not.toHaveBeenCalled();
  });
});
