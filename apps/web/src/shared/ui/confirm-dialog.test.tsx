import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { ConfirmDialog } from "./confirm-dialog";

function renderDialog(open: boolean) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  render(
    <ConfirmDialog
      open={open}
      title="글을 지울까요?"
      description="지운 글은 되돌릴 수 없어요."
      confirmLabel="삭제"
      onConfirm={onConfirm}
      onCancel={onCancel}
    />,
  );
  return { onConfirm, onCancel };
}

describe("ConfirmDialog", () => {
  it("닫혀 있으면 아무것도 그리지 않는다", () => {
    renderDialog(false);

    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("열리면 제목과 설명을 가진 대화상자를 보여 주고 취소 버튼에 초점을 둔다", () => {
    renderDialog(true);

    const dialog = screen.getByRole("alertdialog", { name: "글을 지울까요?" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAccessibleDescription("지운 글은 되돌릴 수 없어요.");
    expect(screen.getByRole("button", { name: "취소" })).toHaveFocus();
  });

  it("확인을 누르면 onConfirm, 취소나 Escape면 onCancel을 부른다", async () => {
    const user = userEvent.setup({ delay: null });
    const { onConfirm, onCancel } = renderDialog(true);

    await user.click(screen.getByRole("button", { name: "삭제" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "취소" }));
    await user.keyboard("{Escape}");
    expect(onCancel).toHaveBeenCalledTimes(2);
  });
});
