import type { Notification } from "./types";

/** 행동한 회원을 알 수 없을 때(탈퇴 등) 닉네임 자리에 쓰는 말. */
const UNKNOWN_ACTOR = "누군가";

/**
 * 알림 한 줄 문구(data-model.md 종류 표). 종류, 행동한 회원의 닉네임, 묶인 인원 수만 쓰고
 * 글이나 댓글 본문은 넣지 않는다(ADR-0005). 토스트와 목록이 같은 문구를 쓴다.
 */
export function notificationMessage(
  notification: Pick<Notification, "type" | "actor" | "actorCount">,
): string {
  const { type, actor, actorCount } = notification;
  const who = actor ? `${actor.nickname} 님` : UNKNOWN_ACTOR;
  const subject = actor ? `${who}이` : who;

  switch (type) {
    case "POST_COMMENT":
      return `${subject} 내 글에 댓글을 남겼어요`;
    case "POST_REPLY":
      return `${subject} 내 글에 답글을 남겼어요`;
    case "COMMENT_REPLY":
      return `${subject} 내 댓글에 답글을 남겼어요`;
    case "POST_LIKE": {
      const others = actorCount - 1;
      return others > 0
        ? `${who} 외 ${others}명이 공감했어요`
        : `${subject} 공감했어요`;
    }
    case "MONSTER_SPAWNED":
      return "몬스터가 나타났어요";
    case "MONSTER_DEFEATED":
      return "내 몬스터가 처치됐어요";
    case "MONSTER_DEFEATED_TOGETHER":
      return "함께 공격한 몬스터가 처치됐어요";
    // 005: 단계 이름이나 글 내용을 싣지 않는다(FR-007).
    case "SUPPORT_NOTICE":
      return "마음이 많이 힘드신가요? 도움받을 수 있는 곳을 안내해 드려요";
    case "CONTENT_RESTORED":
      return "가려졌던 글이 다시 보여요";
    case "REVIEW_KEPT":
      return "요청하신 글을 다시 살펴봤어요";
  }
}
