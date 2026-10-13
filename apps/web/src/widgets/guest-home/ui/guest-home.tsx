"use client";

import { PencilSimpleLine } from "@phosphor-icons/react";
import Link from "next/link";

import { authorMetaLabel } from "@/entities/member";
import {
  EMOTION_LABELS,
  EmotionTag,
  MonsterSummary,
  MonsterThumb,
  type EmotionType,
} from "@/entities/monster";
import { PublicPostCard, usePublicFeedQuery } from "@/entities/post";
import { useGuestAccess } from "@/features/auth/guest-access";
import { Button, ListSkeleton } from "@/shared/ui";

/** 로그인 없이 받는 글의 상한(계약 `PublicFeed.items`의 `maxItems`). 다 찼으면 더 있을 수 있다. */
const PUBLIC_FEED_LIMIT = 20;

const EMOTIONS = Object.keys(EMOTION_LABELS) as EmotionType[];

const STEPS = [
  {
    title: "고민을 적어요",
    body: "요즘 마음을 무겁게 하는 일을 적으면 그 감정이 몬스터로 나타나요.",
  },
  {
    title: "공감과 댓글을 받아요",
    body: "글을 읽은 회원들이 공감을 누르고 댓글을 남겨요.",
  },
  {
    title: "함께 이겨내요",
    body: "공감과 댓글이 모일수록 몬스터가 약해져요.",
  },
];

/**
 * 비회원이 보는 홈. 무엇을 하는 곳인지 알리고 최근 글을 닉네임 없이 목록으로 보인다(011).
 * 글을 누르거나 참여하려 하면 로그인 안내를 연다. 목록이 비었거나 불러오지 못해도 소개와
 * 로그인으로 가는 길은 남는다(011 FR-010).
 * 메뉴는 화면의 메뉴가 잠금 표시와 함께 보여 주므로 여기서 되풀이하지 않는다.
 */
export function GuestHome() {
  const { requestLogin } = useGuestAccess();
  return (
    <div className="flex w-full max-w-[720px] flex-col gap-8">
      <header>
        <h1 className="text-heading text-fg font-bold">마음 이야기</h1>
        <p className="text-body-sm text-fg-subtle mt-1">
          고민을 나누고 감정 몬스터를 함께 이겨내는 곳이에요.
        </p>
      </header>

      <section
        aria-labelledby="guest-home-monsters"
        className="rounded-surface bg-surface-sunken flex flex-col gap-4 p-5"
      >
        <h2 id="guest-home-monsters" className="text-body text-fg font-bold">
          고민은 감정 몬스터가 돼요
        </h2>
        <ul className="grid grid-cols-5 gap-2">
          {EMOTIONS.map((emotion) => (
            <li
              key={emotion}
              className="flex min-w-0 flex-col items-center gap-1.5"
            >
              <MonsterThumb emotion={emotion} stage="full" />
              <span className="text-caption text-fg-muted font-medium break-keep">
                {EMOTION_LABELS[emotion]}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section
        aria-labelledby="guest-home-stories"
        className="flex flex-col gap-3"
      >
        <div>
          <h2
            id="guest-home-stories"
            className="text-title text-fg font-bold break-keep"
          >
            최근 이야기
          </h2>
          <p className="text-body-sm text-fg-subtle mt-1 max-w-md break-keep">
            글의 앞부분을 닉네임 없이 보여드려요. 글 전체와 댓글은 로그인하면 볼
            수 있어요.
          </p>
        </div>
        <RecentStories onSelect={(postId) => requestLogin(`/post/${postId}`)} />
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="text-title text-fg font-bold break-keep">
          로그인하고 함께해요
        </h2>
        <p className="text-body-sm text-fg-subtle max-w-md break-keep">
          공감과 댓글로 몬스터를 함께 물리치고 내 고민도 남길 수 있어요.
        </p>
        <div className="mt-3 flex flex-col gap-2 sm:flex-row">
          <Button type="button" size="lg" onClick={() => requestLogin("/home")}>
            로그인하고 이야기 보기
          </Button>
          <Button
            type="button"
            size="lg"
            variant="secondary"
            onClick={() => requestLogin("/write")}
          >
            <PencilSimpleLine size={18} aria-hidden />
            고민 쓰기
          </Button>
        </div>
        <p className="text-body-sm text-fg-subtle">
          처음이라면{" "}
          <Link
            href="/signup"
            className="text-fg inline-flex min-h-11 items-center font-bold underline-offset-4 hover:underline"
          >
            회원가입
          </Link>
        </p>
      </section>

      <section aria-labelledby="guest-home-steps">
        <h2 id="guest-home-steps" className="text-body text-fg font-bold">
          오구오구는 이렇게 써요
        </h2>
        <ol className="mt-3 grid gap-x-6 gap-y-4 sm:grid-cols-3">
          {STEPS.map((step, index) => (
            <li key={step.title} className="flex gap-3 sm:flex-col sm:gap-2">
              <span
                aria-hidden
                className="bg-surface-sunken text-caption text-fg-muted flex size-7 shrink-0 items-center justify-center rounded-full font-bold tabular-nums"
              >
                {index + 1}
              </span>
              <div>
                <p className="text-body-sm text-fg font-bold">{step.title}</p>
                <p className="text-body-sm text-fg-subtle mt-1 break-keep">
                  {step.body}
                </p>
              </div>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}

/** 최근 글 목록(011 US1). 다음 쪽이 없어 스무 개가 찼으면 더 보려면 로그인하라고 알린다(US1-AC5). */
function RecentStories({ onSelect }: { onSelect: (postId: number) => void }) {
  const { data, isPending, isError, isFetching, refetch } =
    usePublicFeedQuery();
  const items = data?.items ?? [];

  if (isPending) {
    return <ListSkeleton label="최근 이야기" />;
  }

  if (isError && items.length === 0) {
    return (
      <div className="py-2">
        <p role="alert" className="text-body-sm text-danger">
          이야기를 불러오지 못했어요. 잠시 후 다시 시도해주세요.
        </p>
        <Button
          variant="secondary"
          className="mt-3"
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
    return (
      <div className="py-2">
        <p className="text-body text-fg font-bold">
          아직 올라온 이야기가 없어요.
        </p>
        <p className="text-body-sm text-fg-subtle mt-1">
          로그인하고 첫 이야기를 들려주세요.
        </p>
      </div>
    );
  }

  return (
    <>
      <ul className="border-line -mx-5 flex flex-col border-t">
        {items.map((item) => (
          <li key={item.postId} className="border-line border-b">
            <PublicPostCard
              item={item}
              authorMeta={authorMetaLabel(item)}
              renderEmotion={(monster) => (
                <EmotionTag emotion={monster.emotion} />
              )}
              renderMonster={(monster) => <MonsterSummary monster={monster} />}
              onSelect={onSelect}
            />
          </li>
        ))}
      </ul>
      {items.length >= PUBLIC_FEED_LIMIT ? (
        <p className="text-body-sm text-fg-subtle break-keep">
          최근 {PUBLIC_FEED_LIMIT}개까지 보여드려요. 더 많은 이야기는 로그인하면
          볼 수 있어요.
        </p>
      ) : null}
    </>
  );
}
