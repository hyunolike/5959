import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { MonsterLifecycleLoop } from "./monster-lifecycle-loop";

const NAME =
  "고민을 쓰면 몬스터가 나타나고, 공감과 댓글이 모이면 HP가 줄어 쓰러져요";

describe("MonsterLifecycleLoop", () => {
  it("US5-AC3 장면 전체가 하나의 그림이고 장면을 설명하는 이름이 있다", () => {
    render(<MonsterLifecycleLoop />);
    const scene = screen.getByRole("img", { name: NAME });

    // 안의 그림과 글자는 이름과 겹쳐 두 번 읽히지 않게 숨긴다.
    expect(within(scene).queryAllByRole("img")).toHaveLength(0);
    scene.querySelectorAll("img").forEach((img) => {
      expect(img).toHaveAttribute("alt", "");
    });
  });

  it("US5-AC1 쓰기, 등장, HP가 줄어 쓰러짐의 세 칸과 HP 막대가 있다", () => {
    const { container } = render(<MonsterLifecycleLoop />);

    expect(container.querySelectorAll("[data-loop-panel]")).toHaveLength(3);
    expect(container).toHaveTextContent("쓰기");
    expect(container).toHaveTextContent("등장");
    expect(container).toHaveTextContent("함께 물리치기");
    expect(container.querySelector(".ogu-loop-hp-fill")).not.toBeNull();
    // 셋째 칸은 네 단계 그림을 겹쳐 두고 차례로 보인다.
    for (const stage of ["full", "hurt", "weak", "defeated"]) {
      expect(
        container.querySelector(`img[src*="anxiety-${stage}"]`),
      ).not.toBeNull();
    }
  });

  it("US5-AC2 움직임이 없을 때 쓰는 멈춘 그림의 표시가 있다", () => {
    const { container } = render(<MonsterLifecycleLoop />);
    // 움직임 줄이기에서는 쓰러진 그림만 남고 막대는 빈 채로 멈춘다(globals.css의 ogu-loop-*).
    expect(container.querySelector(".ogu-loop-sprite-defeated")).not.toBeNull();
    expect(container.querySelectorAll(".ogu-loop-panel")).toHaveLength(3);
  });

  it("FR-023 그림은 첫 화면의 글보다 늦게 받는다", () => {
    const { container } = render(<MonsterLifecycleLoop />);
    container.querySelectorAll("img").forEach((img) => {
      expect(img).toHaveAttribute("loading", "lazy");
    });
  });
});
