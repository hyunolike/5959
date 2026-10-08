import { render, screen } from "@testing-library/react";
import { useState } from "react";
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

  it("Tab과 Shift+Tab은 대화상자 안에서 돈다", async () => {
    const user = userEvent.setup({ delay: null });
    renderDialog(true);
    const cancel = screen.getByRole("button", { name: "취소" });
    const confirm = screen.getByRole("button", { name: "삭제" });

    expect(cancel).toHaveFocus();
    await user.tab();
    expect(confirm).toHaveFocus();
    await user.tab();
    expect(cancel).toHaveFocus();
    await user.tab({ shift: true });
    expect(confirm).toHaveFocus();
  });

  it("닫히면 연 버튼으로 초점을 돌려준다(Escape와 취소 모두)", async () => {
    const user = userEvent.setup({ delay: null });
    render(<DialogWithTrigger />);
    const trigger = screen.getByRole("button", { name: "열기" });

    await user.click(trigger);
    expect(screen.getByRole("button", { name: "취소" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "취소" }));
    expect(trigger).toHaveFocus();
  });

  it("finalFocus를 주면 닫힐 때 그 요소로 초점을 옮긴다(연 버튼이 사라지는 경우)", async () => {
    const user = userEvent.setup({ delay: null });
    render(<DialogWithTrigger withFallback />);

    await user.click(screen.getByRole("button", { name: "열기" }));
    await user.click(screen.getByRole("button", { name: "삭제" }));

    expect(screen.getByRole("region", { name: "목록" })).toHaveFocus();
  });

  it("부모가 다시 그려져 onCancel이 바뀌어도 초점을 취소 버튼으로 되돌리지 않는다", async () => {
    const user = userEvent.setup({ delay: null });
    const { rerender } = render(
      <ConfirmDialog
        open
        title="t"
        confirmLabel="삭제"
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );
    await user.tab();
    expect(screen.getByRole("button", { name: "삭제" })).toHaveFocus();

    rerender(
      <ConfirmDialog
        open
        title="t"
        confirmLabel="삭제"
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );

    expect(screen.getByRole("button", { name: "삭제" })).toHaveFocus();
  });
});

/** 열기 버튼으로 여는 대화상자. [withFallback]이면 확인 뒤 "목록" 영역으로 초점을 옮긴다. */
function DialogWithTrigger({
  withFallback = false,
}: {
  withFallback?: boolean;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <section aria-label="목록" tabIndex={-1} id="list" />
      <button type="button" onClick={() => setOpen(true)}>
        열기
      </button>
      <ConfirmDialog
        open={open}
        title="지울까요?"
        confirmLabel="삭제"
        finalFocus={
          withFallback ? () => document.getElementById("list") : undefined
        }
        onConfirm={() => setOpen(false)}
        onCancel={() => setOpen(false)}
      />
    </>
  );
}
