import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { Label } from "./label";
import { Select } from "./select";

describe("Select", () => {
  it("DS-US2-AC2 선택 상자는 56px이고 이름표와 이어진다", () => {
    render(
      <>
        <Label htmlFor="jobRole">직군</Label>
        <Select id="jobRole" defaultValue="">
          <option value="" disabled>
            직군을 선택하세요
          </option>
          <option value="DEVELOPMENT">개발</option>
        </Select>
      </>,
    );
    expect(screen.getByLabelText("직군")).toHaveClass(
      "h-14",
      "border-line-strong",
    );
  });

  it("고른 값이 바뀌면 onChange가 불린다", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <Select aria-label="경력" defaultValue="" onChange={onChange}>
        <option value="" disabled>
          경력을 선택하세요
        </option>
        <option value="YEAR_1">1년차</option>
      </Select>,
    );
    await user.selectOptions(screen.getByLabelText("경력"), "YEAR_1");
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText("경력")).toHaveValue("YEAR_1");
  });
});
