import { ChatCircle, Heart } from "@phosphor-icons/react/dist/ssr";
import { useId, type ReactNode } from "react";

import { formatDate, formatRelativeTime } from "@/shared/lib";

import type { PublicFeedItem } from "../model/types";

type MonsterView = NonNullable<PublicFeedItem["monster"]>;

/**
 * 로그인하지 않은 방문자가 보는 글 하나(011 US1-AC2). 닉네임 자리가 없고(US1-AC3), 글 상세는
 * 로그인해야 열리므로 링크가 아니라 버튼이다. 누르면 무엇을 할지는 위젯이 정한다(US1-AC4).
 * 버튼은 카드 전체를 덮는다. 몬스터와 HP 막대가 버튼의 자식이 되면 보조기기가 읽지 못한다.
 */
export function PublicPostCard({
  item,
  authorMeta,
  renderEmotion,
  renderMonster,
  onSelect,
}: {
  item: PublicFeedItem;
  authorMeta: string;
  renderEmotion: (monster: MonsterView) => ReactNode;
  renderMonster: (monster: MonsterView) => ReactNode;
  onSelect: (postId: number) => void;
}) {
  const previewId = useId();
  const relativeTime = formatRelativeTime(item.createdAt);

  return (
    <div className="hover:bg-surface-sunken has-[button:active]:bg-line relative px-5 py-[18px] transition-colors">
      <div className="text-label text-fg-subtle flex flex-wrap items-center gap-x-2 gap-y-1">
        {item.monster ? renderEmotion(item.monster) : null}
        <span className="text-fg font-bold">{authorMeta}</span>
        <time
          dateTime={item.createdAt}
          title={
            relativeTime
              ? formatDate(item.createdAt, "YYYY.MM.DD HH:mm")
              : undefined
          }
          className="ml-auto tabular-nums"
        >
          {relativeTime}
        </time>
      </div>
      <p
        id={previewId}
        className="text-body text-fg mt-2.5 line-clamp-4 break-words whitespace-pre-line"
      >
        {item.contentPreview}
      </p>
      <div className="mt-3.5 flex flex-wrap items-center gap-x-3 gap-y-2">
        <div className="min-w-0 flex-auto">
          {item.monster ? (
            renderMonster(item.monster)
          ) : (
            <p className="text-label text-fg-subtle">분석 중</p>
          )}
        </div>
        <div className="text-label text-fg-muted ml-auto flex shrink-0 items-center gap-3.5 font-medium tabular-nums">
          <span className="inline-flex items-center gap-1">
            <Heart size={18} aria-hidden />
            공감 {item.likeCount}
          </span>
          <span className="inline-flex items-center gap-1">
            <ChatCircle size={18} aria-hidden />
            댓글 {item.commentCount}
          </span>
        </div>
      </div>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-describedby={previewId}
        onClick={() => onSelect(item.postId)}
        className="absolute inset-0 focus-visible:-outline-offset-2"
      >
        <span className="sr-only">로그인하고 이 글 읽기</span>
      </button>
    </div>
  );
}
