import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { PageHeader } from "./page-header";

describe("PageHeader", () => {
  it("POLISH-AC11 안쪽 화면의 머리말은 뒤로 가기와 h1 제목을 가진다", () => {
    render(
      <PageHeader
        title="알림"
        backHref="/home"
        backLabel="마음 이야기로 돌아가기"
      />,
    );
    expect(screen.getByRole("heading", { level: 1, name: "알림" })).toHaveClass(
      "text-body-lg",
      "font-bold",
    );
    expect(
      screen.getByRole("link", { name: "마음 이야기로 돌아가기" }),
    ).toHaveAttribute("href", "/home");
  });

  it("POLISH-AC11 뒤로 가기의 누르는 영역은 44px이다", () => {
    render(<PageHeader title="알림" backHref="/home" backLabel="뒤로" />);
    expect(screen.getByRole("link", { name: "뒤로" })).toHaveClass(
      "size-11",
      "ogu-pressable",
    );
  });

  it("POLISH-AC11 제목이 가운데에 오게 양옆 칸의 너비가 같다", () => {
    const { container } = render(
      <PageHeader title="알림" backHref="/home" backLabel="뒤로" />,
    );
    expect(container.firstElementChild).toHaveClass(
      "grid",
      "grid-cols-[2.75rem_1fr_2.75rem]",
      "h-14",
    );
  });

  it("보이는 제목이 없는 화면은 제목을 읽히기만 하게 둘 수 있다", () => {
    render(
      <PageHeader
        title="고민 글"
        hideTitle
        backHref="/home"
        backLabel="뒤로"
      />,
    );
    expect(
      screen.getByRole("heading", { level: 1, name: "고민 글" }),
    ).toHaveClass("sr-only");
  });
});
