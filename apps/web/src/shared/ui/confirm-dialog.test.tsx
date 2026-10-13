import { act, fireEvent, render, screen } from "@testing-library/react";
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
  it("GUEST-US1-AC2 같은 새로고침 marker를 잡은 닫힌 안내 둘은 history를 한 번만 정리한다", async () => {
    const back = vi.spyOn(history, "back").mockImplementation(() => {});
    history.replaceState({ __oguDialog: "ogu-dialog-reloaded-guide" }, "");
    try {
      render(
        <>
          <ConfirmDialog
            open={false}
            dismissOnBack
            title="로그인 안내"
            confirmLabel="로그인"
            onCancel={() => {}}
            onConfirm={() => {}}
          />
          <ConfirmDialog
            open={false}
            dismissOnBack
            title="설치 안내"
            confirmLabel="확인"
            onCancel={() => {}}
            onConfirm={() => {}}
          />
        </>,
      );
      await act(async () => {});
      expect(back).toHaveBeenCalledTimes(1);
    } finally {
      act(() => {
        history.replaceState({}, "");
        window.dispatchEvent(new PopStateEvent("popstate"));
      });
      back.mockRestore();
    }
  });

  it("GUEST-US1-AC2 안내를 연 빠른 두 번째 클릭은 배경 닫기로 해석하지 않는다", async () => {
    const user = userEvent.setup();
    render(<DialogWithTrigger />);
    await user.click(screen.getByRole("button", { name: "열기" }));
    const dialog = screen.getByRole("alertdialog");
    fireEvent.click(dialog.parentElement!, { detail: 2 });
    expect(dialog).toBeInTheDocument();
  });

  it("닫기 뒤 history 이동이 끝나기 전에 다시 열어도 이전 닫기가 새 안내창을 소비하지 않는다", async () => {
    const user = userEvent.setup({ delay: null });
    const back = vi.spyOn(history, "back").mockImplementation(() => {});
    render(<DialogWithTrigger dismissOnBack />);
    const trigger = screen.getByRole("button", { name: "열기" });
    await user.click(trigger);
    await user.keyboard("{Escape}");
    expect(back).toHaveBeenCalledTimes(1);
    await user.click(trigger);
    await act(async () => {
      history.replaceState({}, "");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(document.body.style.overflow).toBe("hidden");
    act(() => {
      history.replaceState({}, "");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    back.mockRestore();
  });
  it("안내 시트는 배경을 잠그고 브라우저 뒤로 가기로 닫혀 초점을 복구한다", async () => {
    const user = userEvent.setup({ delay: null });
    function Guide() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button onClick={() => setOpen(true)}>방법 보기</button>
          <ConfirmDialog
            open={open}
            title="설치 방법"
            confirmLabel="확인"
            role="dialog"
            presentation="sheet"
            confirmVariant="primary"
            dismissOnBack
            onConfirm={() => setOpen(false)}
            onCancel={() => setOpen(false)}
          />
        </>
      );
    }
    const { container } = render(<Guide />);
    await user.click(screen.getByRole("button", { name: "방법 보기" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(container.inert).toBe(true);
    expect(document.body.style.overflow).toBe("hidden");
    act(() => {
      history.replaceState({}, "");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(document.body.style.overflow).not.toBe("hidden");
    expect(screen.getByRole("button", { name: "방법 보기" })).toHaveFocus();
  });
  it("POLISH-AC9 시트에는 손잡이가 있고 버튼 두 개의 너비가 같다", () => {
    render(
      <ConfirmDialog
        open
        role="dialog"
        presentation="sheet"
        title="로그인이 필요해요"
        confirmLabel="로그인하기"
        cancelLabel="계속 둘러보기"
        onConfirm={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    const dialog = screen.getByRole("dialog", { name: "로그인이 필요해요" });
    expect(dialog).toHaveClass("rounded-t-surface");
    const grabber = dialog.querySelector("[data-sheet-grabber]");
    expect(grabber).toHaveAttribute("aria-hidden", "true");
    const actions = screen.getByRole("button", {
      name: "로그인하기",
    }).parentElement!;
    expect(actions).toHaveClass("grid", "grid-cols-2");
    for (const name of ["로그인하기", "계속 둘러보기"]) {
      expect(screen.getByRole("button", { name })).toHaveClass("min-h-12");
    }
  });

  it("POLISH-AC9 가운데 대화상자에는 손잡이가 없고 버튼은 오른쪽에 모인다", () => {
    renderDialog(true);
    const dialog = screen.getByRole("alertdialog", { name: "글을 지울까요?" });
    expect(dialog.querySelector("[data-sheet-grabber]")).toBeNull();
    expect(
      screen.getByRole("button", { name: "삭제" }).parentElement,
    ).toHaveClass("flex", "justify-end");
  });

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
  dismissOnBack = false,
}: {
  withFallback?: boolean;
  dismissOnBack?: boolean;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <section aria-label="목록" tabIndex={-1} id="list" />
      <button type="button" onClick={() => setOpen(true)}>
        열기
      </button>
      <ConfirmDialog
        dismissOnBack={dismissOnBack}
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
