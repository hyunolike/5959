"use client";

import type {
  InfiniteData,
  UseInfiniteQueryResult,
} from "@tanstack/react-query";
import Link from "next/link";
import type { ReactNode } from "react";

import { useInfiniteScroll } from "@/shared/lib";
import { Button, EmptyState, ListSkeleton, Spinner } from "@/shared/ui";

interface ActivityPage<T> {
  items: T[];
  nextCursor: string | null;
}

/**
 * 마이페이지 탭 하나의 목록: 불러오는 중, 실패, 빈 상태, 무한 스크롤을 탭들이 같이 쓴다.
 * 줄 모양 항목(글, 댓글)은 구분선으로(`rows`), 카드 모양 항목(주간 리포트)은 간격으로(`cards`) 나눈다.
 * 항목이 없으면 안내와 다음 행동(글쓰기, 피드 보기)을 보인다(US3-AC4).
 */
export function ActivityList<T>({
  query,
  label,
  emptyMessage,
  keyOf,
  renderItem,
  layout = "rows",
}: {
  query: UseInfiniteQueryResult<InfiniteData<ActivityPage<T>>>;
  label: string;
  emptyMessage: string;
  keyOf: (item: T) => number;
  renderItem: (item: T) => ReactNode;
  layout?: "rows" | "cards";
}) {
  const {
    data,
    isPending,
    isError,
    isFetchNextPageError,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
    refetch,
    isFetching,
  } = query;
  const items = data?.pages.flatMap((page) => page.items) ?? [];
  const loadNext = () => void fetchNextPage({ cancelRefetch: false });
  const sentinel = useInfiniteScroll(
    loadNext,
    hasNextPage && !isFetchingNextPage && !isError,
  );

  if (isPending) {
    return <ListSkeleton label={label} />;
  }

  if (isError && items.length === 0) {
    return (
      <EmptyState
        tone="error"
        title="목록을 불러오지 못했습니다."
        description="잠시 후 다시 시도해주세요."
      >
        <Button
          variant="secondary"
          disabled={isFetching}
          aria-busy={isFetching}
          onClick={() => void refetch()}
        >
          다시 불러오기
        </Button>
      </EmptyState>
    );
  }

  if (items.length === 0) {
    return (
      <EmptyState imageSrc="/brand/ogu-hello.webp" title={emptyMessage}>
        <Button asChild>
          <Link href="/write">글쓰기</Link>
        </Button>
        <Button asChild variant="secondary">
          <Link href="/home">피드 보기</Link>
        </Button>
      </EmptyState>
    );
  }

  return (
    <>
      <ul
        aria-label={label}
        className={
          layout === "cards"
            ? "flex flex-col gap-3"
            : "border-line divide-line flex flex-col divide-y border-y"
        }
      >
        {items.map((item) => (
          <li key={keyOf(item)}>{renderItem(item)}</li>
        ))}
      </ul>
      <div ref={sentinel} aria-hidden className="h-px" />
      {isError ? (
        <div>
          <p role="alert" className="text-body-sm text-danger">
            {isFetchNextPageError
              ? "다음 목록을 불러오지 못했어요."
              : "목록을 새로 확인하지 못했어요."}
          </p>
          <Button
            variant="secondary"
            className="mt-3"
            disabled={isFetching}
            onClick={() => (isFetchNextPageError ? loadNext() : void refetch())}
          >
            다시 불러오기
          </Button>
        </div>
      ) : isFetchingNextPage ? (
        <div className="flex justify-center py-4">
          <Spinner />
        </div>
      ) : hasNextPage ? (
        // 스크롤을 못 쓰는 환경(키보드, 보조기기)에서도 다음 쪽을 부를 수 있게 둔다
        <Button variant="secondary" onClick={loadNext}>
          더 보기
        </Button>
      ) : null}
    </>
  );
}
