import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";

import type { FeedFilter } from "@/entities/post";

import { FeedControls } from "./feed-controls";

function Harness({ initial }: { initial: FeedFilter }) {
  const [filter, setFilter] = useState(initial);
  return <FeedControls filter={filter} onChange={setFilter} />;
}

const ONE_FILTER: FeedFilter = {
  order: "LATEST",
  jobRoles: ["DEVELOPMENT"],
  careerYears: [],
};

function panel() {
  return document.querySelector("details") as HTMLDetailsElement;
}

describe("FeedControls", () => {
  it("FR-011 켜져 있던 마지막 필터를 꺼도 필터 영역이 닫히지 않고 초점이 칩에 남는다", async () => {
    const user = userEvent.setup();
    render(<Harness initial={ONE_FILTER} />);
    expect(panel().open).toBe(true);

    const chip = screen.getByRole("button", { name: "개발", pressed: true });
    await user.click(chip);

    expect(chip).toHaveAttribute("aria-pressed", "false");
    expect(panel().open).toBe(true);
    expect(chip).toHaveFocus();
  });

  it("FR-011 필터 지우기를 누르면 영역은 열린 채로 초점이 필터 여닫이로 간다", async () => {
    const user = userEvent.setup();
    render(<Harness initial={ONE_FILTER} />);

    await user.click(screen.getByRole("button", { name: "필터 지우기" }));

    expect(
      screen.queryByRole("button", { name: "필터 지우기" }),
    ).not.toBeInTheDocument();
    expect(panel().open).toBe(true);
    expect(panel().querySelector("summary")).toHaveFocus();
  });

  it("FR-011 필터가 없으면 닫힌 채로 시작하고, 여닫이에는 방향이 바뀌는 화살표가 있다", () => {
    render(
      <Harness initial={{ order: "LATEST", jobRoles: [], careerYears: [] }} />,
    );
    expect(panel().open).toBe(false);
    expect(panel()).toHaveClass("group");
    const caret = panel().querySelector("summary svg:last-of-type");
    expect(caret).toHaveClass("group-open:rotate-180");
  });
});
