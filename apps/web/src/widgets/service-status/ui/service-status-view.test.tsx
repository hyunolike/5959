import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ServiceStatusView } from "./service-status-view";

describe("ServiceStatusView", () => {
  it("US1-AC1 UP이면 서버 정상을 보여 준다", () => {
    render(<ServiceStatusView status="UP" />);
    expect(screen.getByRole("status")).toHaveTextContent("서버 정상");
  });

  it("US1-AC2 DOWN이면 서버 점검 중을 보여 준다", () => {
    render(<ServiceStatusView status="DOWN" />);
    expect(screen.getByRole("status")).toHaveTextContent("서버 점검 중");
  });

  it("확인 중에는 확인 중 문구를 보여 준다", () => {
    render(<ServiceStatusView status="LOADING" />);
    expect(screen.getByRole("status")).toHaveTextContent("서버 상태 확인 중");
  });
});
