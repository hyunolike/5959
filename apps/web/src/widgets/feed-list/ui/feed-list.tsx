"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useRef } from "react";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import { MonsterPlaceholder } from "@/entities/monster";
import { PostCard, useFeedQuery, type FeedFilter } from "@/entities/post";
import { Button, Card, Spinner } from "@/shared/ui";

import { parseFeedFilter, toFeedSearchParams } from "../model/filter-params";
import { FeedControls } from "./feed-controls";

/**
 * 피드(US2): 정렬 토글, 직군과 경력 다중 선택 필터, 무한 스크롤.
 * 정렬과 필터는 주소의 검색어에 둔다. 새로고침이나 뒤로 가기에도 남는다.
 */
export function FeedList() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const search = searchParams.toString();
  const filter = useMemo(
    () => parseFeedFilter(new URLSearchParams(search)),
    [search],
  );

  const changeFilter = (next: FeedFilter) => {
    const query = toFeedSearchParams(next).toString();
    router.replace(query ? `${pathname}?${query}` : pathname, {
      scroll: false,
    });
  };

  return (
    <section aria-label="피드" className="flex w-full flex-col gap-4">
      <FeedControls filter={filter} onChange={changeFilter} />
      <FeedItems filter={filter} />
    </section>
  );
}

function FeedItems({ filter }: { filter: FeedFilter }) {
  const {
    data,
    isPending,
    isError,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
  } = useFeedQuery(filter);
  const items = data?.pages.flatMap((page) => page.items) ?? [];
  const sentinel = useInfiniteScroll(
    () => void fetchNextPage(),
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
          피드를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      </Card>
    );
  }

  if (items.length === 0) {
    const filtered =
      filter.jobRoles.length > 0 || filter.careerYears.length > 0;
    return (
      <Card className="text-center">
        <p className="text-sm text-neutral-600">
          {filtered
            ? "조건에 맞는 고민이 없어요."
            : "아직 올라온 고민이 없어요."}
        </p>
      </Card>
    );
  }

  return (
    <>
      <ul className="flex flex-col gap-3">
        {items.map((item) => (
          <li key={item.postId}>
            <PostCard
              item={item}
              authorMeta={`${JOB_ROLE_LABELS[item.author.jobRole]} · ${CAREER_YEAR_LABELS[item.author.careerYear]}`}
              renderMonster={(monster) => (
                <MonsterPlaceholder monster={monster} />
              )}
            />
          </li>
        ))}
      </ul>
      <div ref={sentinel} aria-hidden className="h-px" />
      {isFetchingNextPage ? (
        <div className="flex justify-center py-4">
          <Spinner />
        </div>
      ) : hasNextPage ? (
        // 스크롤을 못 쓰는 환경(키보드, 보조기기)에서도 다음 쪽을 부를 수 있게 둔다
        <Button variant="outline" onClick={() => void fetchNextPage()}>
          더 보기
        </Button>
      ) : null}
    </>
  );
}

/** 목록 끝의 표지가 화면에 들어오면 [onReach]를 부른다. [enabled]가 거짓이면 보지 않는다. */
function useInfiniteScroll(onReach: () => void, enabled: boolean) {
  const ref = useRef<HTMLDivElement | null>(null);
  const onReachRef = useRef(onReach);

  useEffect(() => {
    onReachRef.current = onReach;
  });

  useEffect(() => {
    const target = ref.current;
    if (!enabled || !target) {
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          onReachRef.current();
        }
      },
      { rootMargin: "200px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [enabled]);

  return ref;
}
