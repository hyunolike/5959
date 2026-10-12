"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import Image from "next/image";
import Link from "next/link";
import { useMemo } from "react";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import { EmotionTag, MonsterSummary } from "@/entities/monster";
import { PostCard, useFeedQuery, type FeedFilter } from "@/entities/post";
import { useInfiniteScroll } from "@/shared/lib";
import { Button, ListSkeleton, Spinner } from "@/shared/ui";

import {
  parseFeedFilter,
  toFeedSearchParams,
  uniqueItems,
} from "../model/filter-params";
import { FeedControls } from "./feed-controls";

/** 목록, 빈 상태, 오류, 로딩이 같은 흰 읽기 면을 쓴다. */
const SURFACE_CLASS_NAME =
  "overflow-hidden rounded-surface border border-neutral-200 bg-white";

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
    isFetchNextPageError,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
    refetch,
    isFetching,
  } = useFeedQuery(filter);
  const items = uniqueItems(data?.pages ?? []);
  const sentinel = useInfiniteScroll(
    () => void fetchNextPage({ cancelRefetch: false }),
    hasNextPage && !isFetchingNextPage && !isError,
  );

  if (isPending) {
    return (
      <div className={SURFACE_CLASS_NAME}>
        <ListSkeleton label="피드" />
      </div>
    );
  }

  if (isError && items.length === 0) {
    return (
      <div className={`${SURFACE_CLASS_NAME} px-5 py-10 text-center`}>
        <Image
          src="/brand/ogu-companion.png"
          alt=""
          width={96}
          height={79}
          className="mx-auto mb-4 h-auto w-16 object-contain"
        />
        <p role="alert" className="text-sm text-red-600">
          피드를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
        <Button
          variant="secondary"
          className="mt-4"
          disabled={isFetching}
          aria-busy={isFetching}
          onClick={() => void refetch()}
        >
          다시 불러오기
        </Button>
      </div>
    );
  }

  if (items.length === 0) {
    const filtered =
      filter.jobRoles.length > 0 || filter.careerYears.length > 0;
    return (
      <div className={`${SURFACE_CLASS_NAME} px-5 py-10 text-center`}>
        <Image
          src="/brand/ogu-companion.png"
          alt=""
          width={96}
          height={79}
          className="mx-auto mb-4 h-auto w-16 object-contain"
        />
        <p className="font-bold text-neutral-900">
          {filtered
            ? "조건에 맞는 고민이 없어요."
            : "아직 올라온 고민이 없어요."}
        </p>
        <p className="mt-1 text-sm text-neutral-600">
          {filtered
            ? "다른 직군이나 경력으로도 찾아보세요."
            : "첫 이야기를 들려주세요."}
        </p>
        {!filtered ? (
          <Button asChild className="mt-5">
            <Link href="/write">첫 고민 남기기</Link>
          </Button>
        ) : null}
      </div>
    );
  }

  return (
    <>
      <ul
        className={`${SURFACE_CLASS_NAME} flex flex-col [&>li:last-child>a]:border-b-0`}
      >
        {items.map((item) => (
          <li key={item.postId}>
            <PostCard
              item={item}
              authorMeta={`${JOB_ROLE_LABELS[item.author.jobRole]} · ${CAREER_YEAR_LABELS[item.author.careerYear]}`}
              showCreatedAt
              renderEmotion={(monster) => (
                <EmotionTag emotion={monster.emotion} />
              )}
              renderMonster={(monster) => <MonsterSummary monster={monster} />}
            />
          </li>
        ))}
      </ul>
      <div ref={sentinel} aria-hidden className="h-px" />
      {isError ? (
        <div className="py-4">
          <p role="alert" className="text-sm text-red-600">
            {isFetchNextPageError
              ? "다음 이야기를 불러오지 못했어요."
              : "새 이야기를 확인하지 못했어요."}
          </p>
          <Button
            variant="secondary"
            className="mt-3"
            disabled={isFetching}
            onClick={() =>
              isFetchNextPageError
                ? void fetchNextPage({ cancelRefetch: false })
                : void refetch()
            }
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
        <Button
          variant="secondary"
          className="self-center"
          onClick={() => void fetchNextPage({ cancelRefetch: false })}
        >
          더 보기
        </Button>
      ) : null}
    </>
  );
}
