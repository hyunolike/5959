"use client";

import Link from "next/link";
import { useState } from "react";

import {
  MonsterCard,
  useMonsterCardsQuery,
  type EmotionType,
  type MonsterCardData,
} from "@/entities/monster";
import { Button, Card, ListSkeleton } from "@/shared/ui";

import { CardDialog } from "./card-dialog";
import { CollectionSummary } from "./collection-summary";

/**
 * 몬스터 도감(010). 물리친 몬스터가 카드로 모인다. 위에는 모은 수와 감정별 장수, 아래에는 최근에 물리친 것부터
 * 카드가 놓인다. 카드를 누르면 크게 보인다. 카드 수를 늘리라고 재촉하는 것(순위, 기한, 연속 기록)은 두지 않는다(FR-016).
 */
export function MonsterCollection() {
  const [emotion, setEmotion] = useState<EmotionType | null>(null);
  const [opened, setOpened] = useState<MonsterCardData | null>(null);
  const {
    data,
    isPending,
    isError,
    isFetching,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
    refetch,
  } = useMonsterCardsQuery(emotion);

  if (isPending) {
    return <ListSkeleton label="몬스터 도감" />;
  }
  const summary = data?.pages[0]?.summary;
  if (!summary) {
    return (
      <Card className="text-center">
        <p role="alert" className="text-body-sm text-danger">
          도감을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
        <Button
          variant="secondary"
          className="mt-4"
          disabled={isFetching}
          aria-busy={isFetching}
          onClick={() => void refetch()}
        >
          다시 시도
        </Button>
      </Card>
    );
  }
  if (summary.total === 0) {
    return <EmptyCollection />;
  }

  const cards = data.pages.flatMap((page) => page.items);
  return (
    <div className="flex flex-col gap-5">
      <CollectionSummary
        summary={summary}
        selected={emotion}
        onSelect={setEmotion}
      />
      <ul
        aria-label="모은 카드 목록"
        className="grid grid-cols-2 gap-3 sm:grid-cols-3"
      >
        {cards.map((card) => (
          <li key={card.cardNo}>
            <button
              type="button"
              aria-haspopup="dialog"
              className="ogu-pressable rounded-control block w-full"
              onClick={() => setOpened(card)}
            >
              <MonsterCard card={card} />
            </button>
          </li>
        ))}
      </ul>
      {isError ? (
        <p role="alert" className="text-body-sm text-danger">
          카드를 더 불러오지 못했어요.
        </p>
      ) : null}
      {hasNextPage ? (
        <Button
          variant="secondary"
          disabled={isFetchingNextPage}
          aria-busy={isFetchingNextPage}
          onClick={() => void fetchNextPage({ cancelRefetch: false })}
        >
          더 보기
        </Button>
      ) : null}
      {opened ? (
        <CardDialog card={opened} onClose={() => setOpened(null)} />
      ) : null}
    </div>
  );
}

function EmptyCollection() {
  return (
    <Card className="flex flex-col items-center gap-3 text-center">
      <p className="text-body text-fg font-bold">아직 모은 카드가 없어요</p>
      <p className="text-body-sm text-fg-subtle max-w-sm break-keep">
        내 글의 몬스터가 물리쳐지거나, 다른 회원의 몬스터를 공감과 댓글로 함께
        물리치면 카드가 생겨요.
      </p>
      <div className="mt-1 flex flex-col gap-2 sm:flex-row">
        <Button asChild>
          <Link href="/write">고민 쓰기</Link>
        </Button>
        <Button asChild variant="secondary">
          <Link href="/home">이야기 보러 가기</Link>
        </Button>
      </div>
    </Card>
  );
}
