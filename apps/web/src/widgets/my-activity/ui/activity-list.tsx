"use client";

import type {
  InfiniteData,
  UseInfiniteQueryResult,
} from "@tanstack/react-query";
import Link from "next/link";
import type { ReactNode } from "react";

import { useInfiniteScroll } from "@/shared/lib";
import { Button, Card, Spinner } from "@/shared/ui";

interface ActivityPage<T> {
  items: T[];
  nextCursor: string | null;
}

/**
 * 마이페이지 탭 하나의 목록: 불러오는 중, 실패, 빈 상태, 무한 스크롤을 세 탭이 같이 쓴다.
 * 항목이 없으면 안내와 다음 행동(글쓰기, 피드 보기)을 보인다(US3-AC4).
 */
export function ActivityList<T>({
  query,
  label,
  emptyMessage,
  keyOf,
  renderItem,
}: {
  query: UseInfiniteQueryResult<InfiniteData<ActivityPage<T>>>;
  label: string;
  emptyMessage: string;
  keyOf: (item: T) => number;
  renderItem: (item: T) => ReactNode;
}) {
  const {
    data,
    isPending,
    isError,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
  } = query;
  const items = data?.pages.flatMap((page) => page.items) ?? [];
  const loadNext = () => void fetchNextPage({ cancelRefetch: false });
  const sentinel = useInfiniteScroll(
    loadNext,
    hasNextPage && !isFetchingNextPage,
  );

  if (isPending) {
    return (
      <div className="flex justify-center py-8">
        <Spinner />
      </div>
    );
  }

  if (isError && items.length === 0) {
    return (
      <Card className="text-center">
        <p role="alert" className="text-sm text-red-600">
          목록을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      </Card>
    );
  }

  if (items.length === 0) {
    return (
      <Card className="flex flex-col items-center gap-4 text-center">
        <p className="text-sm text-neutral-600">{emptyMessage}</p>
        <div className="flex gap-2">
          <Button asChild size="sm">
            <Link href="/write">글쓰기</Link>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link href="/home">피드 보기</Link>
          </Button>
        </div>
      </Card>
    );
  }

  return (
    <>
      <ul aria-label={label} className="flex flex-col gap-3">
        {items.map((item) => (
          <li key={keyOf(item)}>{renderItem(item)}</li>
        ))}
      </ul>
      <div ref={sentinel} aria-hidden className="h-px" />
      {isFetchingNextPage ? (
        <div className="flex justify-center py-4">
          <Spinner />
        </div>
      ) : hasNextPage ? (
        // 스크롤을 못 쓰는 환경(키보드, 보조기기)에서도 다음 쪽을 부를 수 있게 둔다
        <Button variant="outline" onClick={loadNext}>
          더 보기
        </Button>
      ) : null}
    </>
  );
}
