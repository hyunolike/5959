import { describe, expect, it } from "vitest";

import { notificationMessage } from "./message";
import type { Notification, NotificationType } from "./types";

function notification(
  type: NotificationType,
  overrides: Partial<Notification> = {},
): Notification {
  return {
    notificationId: 1,
    seq: 1,
    type,
    postId: 10,
    post: { postId: 10, contentPreview: "본문 미리보기는 문구에 쓰지 않는다" },
    commentId: null,
    actor: { id: 2, nickname: "오구" },
    actorCount: 1,
    read: false,
    createdAt: "2026-10-06T00:00:00Z",
    updatedAt: "2026-10-06T00:00:00Z",
    ...overrides,
  };
}

describe("notificationMessage", () => {
  it("US1-AC1 댓글과 답글 알림은 닉네임과 종류 문구로 만든다", () => {
    expect(notificationMessage(notification("POST_COMMENT"))).toBe(
      "오구 님이 내 글에 댓글을 남겼어요",
    );
    expect(notificationMessage(notification("POST_REPLY"))).toBe(
      "오구 님이 내 글에 답글을 남겼어요",
    );
    expect(notificationMessage(notification("COMMENT_REPLY"))).toBe(
      "오구 님이 내 댓글에 답글을 남겼어요",
    );
  });

  it("US1-AC2 공감이 한 명이면 '외 N명'을 뺀다", () => {
    expect(
      notificationMessage(notification("POST_LIKE", { actorCount: 1 })),
    ).toBe("오구 님이 공감했어요");
  });

  it("US1-AC2 공감이 묶이면 N은 actorCount에서 1을 뺀 수다", () => {
    expect(
      notificationMessage(notification("POST_LIKE", { actorCount: 2 })),
    ).toBe("오구 님 외 1명이 공감했어요");
    expect(
      notificationMessage(notification("POST_LIKE", { actorCount: 13 })),
    ).toBe("오구 님 외 12명이 공감했어요");
  });

  it("US1-AC3 몬스터 생성 알림", () => {
    expect(
      notificationMessage(notification("MONSTER_SPAWNED", { actor: null })),
    ).toBe("몬스터가 나타났어요");
  });

  it("US1-AC4 처치 알림은 글쓴이와 함께 공격한 회원의 문구가 다르다", () => {
    expect(
      notificationMessage(notification("MONSTER_DEFEATED", { actor: null })),
    ).toBe("내 몬스터가 처치됐어요");
    expect(
      notificationMessage(
        notification("MONSTER_DEFEATED_TOGETHER", { actor: null }),
      ),
    ).toBe("함께 공격한 몬스터가 처치됐어요");
  });

  it("행동한 회원을 알 수 없으면 닉네임 자리에 '누군가'를 쓴다", () => {
    expect(
      notificationMessage(notification("POST_COMMENT", { actor: null })),
    ).toBe("누군가 내 글에 댓글을 남겼어요");
    expect(
      notificationMessage(
        notification("POST_LIKE", { actor: null, actorCount: 3 }),
      ),
    ).toBe("누군가 외 2명이 공감했어요");
  });

  it("글이나 댓글 본문은 문구에 넣지 않는다(ADR-0005)", () => {
    const types: NotificationType[] = [
      "POST_COMMENT",
      "POST_REPLY",
      "COMMENT_REPLY",
      "POST_LIKE",
      "MONSTER_SPAWNED",
      "MONSTER_DEFEATED",
      "MONSTER_DEFEATED_TOGETHER",
    ];
    for (const type of types) {
      expect(notificationMessage(notification(type))).not.toContain("미리보기");
    }
  });
});
