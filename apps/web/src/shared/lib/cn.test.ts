import { describe, expect, it } from "vitest";

import { cn } from "./cn";

describe("cn", () => {
  it("merges class names and drops falsy values", () => {
    expect(cn("a", false, undefined, "b")).toBe("a b");
  });

  it("lets a later Tailwind class win over a conflicting earlier one", () => {
    expect(cn("px-2", "px-4")).toBe("px-4");
  });

  it("DS-US1-AC4 글자 크기와 글자 색을 함께 주면 둘 다 남는다", () => {
    expect(cn("text-body", "text-fg")).toBe("text-body text-fg");
    expect(cn("text-fg", "text-caption")).toBe("text-fg text-caption");
  });

  it("DS-US1-AC4 글자 크기끼리는 뒤의 것이 이긴다", () => {
    expect(cn("text-body", "text-title")).toBe("text-title");
  });

  it("모서리 이름끼리는 뒤의 것이 이긴다", () => {
    expect(cn("rounded-control", "rounded-surface")).toBe("rounded-surface");
  });

  it("POLISH-AC1 작은 모서리와 새 그림자 이름도 뒤의 것이 이긴다", () => {
    expect(cn("rounded-control", "rounded-control-sm")).toBe(
      "rounded-control-sm",
    );
    expect(cn("shadow-float", "shadow-raised")).toBe("shadow-raised");
    expect(cn("shadow-raised", "shadow-none")).toBe("shadow-none");
  });

  it("게임 그림자는 뒤에 온 그림자 없음에 진다", () => {
    expect(cn("shadow-game", "shadow-none")).toBe("shadow-none");
  });
});
