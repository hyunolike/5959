import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { Notification } from "../model/types";

import { NotificationItem } from "./notification-item";

function notification(overrides: Partial<Notification> = {}): Notification {
  return {
    notificationId: 1,
    seq: 5,
    type: "POST_COMMENT",
    postId: 10,
    post: { postId: 10, contentPreview: "내일 발표가 걱정돼요" },
    commentId: 3,
    actor: { id: 2, nickname: "오구" },
    actorCount: 1,
    read: false,
    createdAt: "2026-10-05T01:00:00Z",
    updatedAt: "2026-10-06T03:04:00Z",
    ...overrides,
  };
}

describe("NotificationItem", () => {
  it("US2-AC1 문구, 글 앞부분, 시각, 안 읽음을 글자로 보여 주고 글 상세로 가는 링크다", () => {
    render(
      <NotificationItem notification={notification()} onSelect={vi.fn()} />,
    );

    const link = screen.getByRole("link");
    expect(link).toHaveAttribute("href", "/post/10");
    expect(link).toHaveTextContent("오구 님이 내 글에 댓글을 남겼어요");
    expect(link).toHaveTextContent("내일 발표가 걱정돼요");
    expect(link).toHaveTextContent("안 읽음");
    // 묶인 공감은 마지막 공감 시각이 기준이라 갱신 시각을 쓴다.
    expect(link.querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-10-06T03:04:00Z",
    );
  });

  it("US2-AC1 읽은 알림은 '읽음'으로 알린다", () => {
    render(
      <NotificationItem
        notification={notification({ read: true })}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByRole("link")).toHaveTextContent("읽음");
    expect(screen.getByRole("link")).not.toHaveTextContent("안 읽음");
  });

  it("누르면 그 알림으로 onSelect를 부른다", () => {
    const onSelect = vi.fn();
    const item = notification();
    render(<NotificationItem notification={item} onSelect={onSelect} />);
    const link = screen.getByRole("link");
    // jsdom은 이동하지 않는다. 링크의 기본 동작만 막아 둔다.
    link.addEventListener("click", (event) => event.preventDefault());

    fireEvent.click(link);

    expect(onSelect).toHaveBeenCalledWith(item);
  });

  it("US2-AC5 post가 null이면 글 앞부분 대신 '삭제된 글'을 보이고 링크가 아니라 버튼이다", () => {
    const onSelect = vi.fn();
    const item = notification({ post: null });
    render(<NotificationItem notification={item} onSelect={onSelect} />);

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    const button = screen.getByRole("button");
    expect(button).toHaveTextContent("삭제된 글");
    expect(button).toHaveTextContent("오구 님이 내 글에 댓글을 남겼어요");

    fireEvent.click(button);

    expect(onSelect).toHaveBeenCalledWith(item);
  });
});
