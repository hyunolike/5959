import { render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { MonsterView } from "../model/types";
import { HIT_REACTION_MS, MonsterPlaceholder } from "./monster-placeholder";

const MONSTER: MonsterView = {
  emotion: "ANXIETY",
  hp: 10,
  maxHp: 10,
  status: "ALIVE",
};

function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => ({
      matches: reduce && query === "(prefers-reduced-motion: reduce)",
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
}

let animate: ReturnType<typeof vi.fn>;

beforeEach(() => {
  animate = vi.fn();
  // jsdom에는 Web Animations API가 없다.
  Object.defineProperty(HTMLElement.prototype, "animate", {
    configurable: true,
    value: animate,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete (HTMLElement.prototype as Partial<HTMLElement>).animate;
});

describe("MonsterPlaceholder 맞는 반응", () => {
  it("US3-AC10 HP가 줄면 HP 바를 흔들고 바뀐 HP를 바로 보여 준다", () => {
    stubReducedMotion(false);
    const { rerender, getByText } = render(
      <MonsterPlaceholder monster={MONSTER} />,
    );
    expect(animate).not.toHaveBeenCalled();

    rerender(<MonsterPlaceholder monster={{ ...MONSTER, hp: 9 }} />);

    expect(getByText("HP 9/10")).toBeInTheDocument();
    expect(animate).toHaveBeenCalledTimes(1);
    expect(animate.mock.calls[0][1]).toMatchObject({
      duration: HIT_REACTION_MS,
    });
  });

  it("HP가 늘거나 그대로면(서버 값으로 맞출 때) 흔들지 않는다", () => {
    stubReducedMotion(false);
    const { rerender } = render(
      <MonsterPlaceholder monster={{ ...MONSTER, hp: 7 }} />,
    );
    rerender(<MonsterPlaceholder monster={{ ...MONSTER, hp: 9 }} />);
    rerender(<MonsterPlaceholder monster={{ ...MONSTER, hp: 9 }} />);
    expect(animate).not.toHaveBeenCalled();
  });

  it("움직임 줄이기가 켜져 있으면 흔들지 않고 HP만 바꾼다", () => {
    stubReducedMotion(true);
    const { rerender, getByText } = render(
      <MonsterPlaceholder monster={MONSTER} />,
    );
    rerender(<MonsterPlaceholder monster={{ ...MONSTER, hp: 9 }} />);

    expect(getByText("HP 9/10")).toBeInTheDocument();
    expect(animate).not.toHaveBeenCalled();
  });
});

describe("MonsterPlaceholder 처치", () => {
  it("US3-AC5 처치된 몬스터는 처치됨을 보여 준다", () => {
    stubReducedMotion(false);
    const { getByText, rerender, queryByText } = render(
      <MonsterPlaceholder monster={MONSTER} />,
    );
    expect(queryByText("처치됨")).not.toBeInTheDocument();

    rerender(
      <MonsterPlaceholder
        monster={{ ...MONSTER, hp: 0, status: "DEFEATED" }}
      />,
    );
    expect(getByText("처치됨")).toBeInTheDocument();
    expect(getByText("HP 0/10")).toBeInTheDocument();
  });
});
