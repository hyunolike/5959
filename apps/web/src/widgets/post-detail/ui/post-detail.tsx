"use client";

import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";

import { authorMetaLabel } from "@/entities/member";
import { emotionLabel, MonsterDisplay, MonsterStage } from "@/entities/monster";
import {
  COMMENT_TONE_LABELS,
  DELETED_POST_NOTICE,
  usePostDetailQuery,
  type PostDetail as PostDetailData,
} from "@/entities/post";
import { PostLikeButton } from "@/features/like";
import { DeletePostButton } from "@/features/manage-post";
import { ReportButton } from "@/features/report-content";
import { RequestReviewButton } from "@/features/request-review";
import { ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";
import {
  AI_FRIENDS_PATH,
  AiBadge,
  Button,
  Card,
  EmptyState,
  PageHeader,
  Spinner,
} from "@/shared/ui";

import { PostComments } from "./post-comments";
import { SafetyNotice } from "./safety-notice";
import { SimilarPosts } from "./similar-posts";

/** 글을 보여 주지 못할 때의 안내. 오구와 함께 돌아갈 곳을 준다. */
function Unavailable({ message }: { message: string }) {
  return (
    <div className="w-full max-w-xl">
      {/* 돌아갈 길은 아래의 버튼이다. 머리말의 뒤로 가기를 또 두면 같은 이름의 링크가 둘이 된다. */}
      <h1 className="sr-only">고민 글</h1>
      {/* 지워진 글도 오류도 화면이 바뀐 까닭이라 alert로 읽힌다. */}
      <EmptyState
        tone="error"
        imageSrc="/brand/ogu-listen.webp"
        title={message}
      >
        <Button asChild variant="secondary">
          <Link href="/home">마음 이야기로 돌아가기</Link>
        </Button>
      </EmptyState>
    </div>
  );
}

/** 없거나 지운 글(404), 숫자가 아닌 글 ID(400). */
export function PostNotFound() {
  return <Unavailable message={DELETED_POST_NOTICE} />;
}

function isNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError && (error.status === 404 || error.status === 400)
  );
}

/**
 * 글 상세(FR-012): 작성자, 본문, 댓글 말투, 몬스터(US5), 공감과 댓글.
 * 몬스터가 생기기 전에는 "분석 중"을 보여 주고, `usePostDetailQuery`가 몬스터가
 * 생길 때까지 다시 불러와 새로고침 없이 바꿔 그린다(FR-015, US1-AC3).
 * 공감과 댓글은 응답 전에 HP를 줄여 보여 주고 몬스터 자리가 맞는 반응을 한다(US3-AC10).
 * 내 글이면 고치기 화면으로 가는 링크와 삭제 버튼을 둔다(US4-AC1, AC2).
 */
export function PostDetail({ postId }: { postId: number }) {
  const { data, error, isPending } = usePostDetailQuery(postId);

  if (isPending) {
    return (
      // 좁은 화면에는 하단 메뉴가 없다. 불러오는 동안에도 돌아갈 길과 화면의 제목을 둔다.
      <div className="flex w-full max-w-xl flex-col gap-4">
        <PageHeader
          title="고민 글"
          hideTitle
          backHref="/home"
          backLabel="마음 이야기로 돌아가기"
          className="-my-2"
        />
        <Card className="flex w-full items-center justify-center py-12 sm:py-12">
          <Spinner label="글을 불러오는 중" />
        </Card>
      </div>
    );
  }

  if (isNotFound(error)) {
    return <PostNotFound />;
  }

  if (!data) {
    return (
      <Unavailable message="글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요." />
    );
  }

  return <PostDetailContent detail={data} />;
}

function PostDetailContent({ detail }: { detail: PostDetailData }) {
  const { author, monster } = detail;
  const queryClient = useQueryClient();
  // 몬스터 없이 열린 화면에서만, 몬스터가 생겼을 때 화면 낭독기에 알린다.
  const [openedWithoutMonster] = useState(!monster);

  return (
    <div className="flex w-full max-w-xl flex-col gap-4">
      {/*
        좁은 화면에서는 하단 메뉴 자리를 댓글 입력줄이 쓴다. 돌아가는 길은 이 머리말이다.
        제목은 보이지 않게 두어, 화면 낭독기로는 누구의 글인지부터 듣게 한다.
      */}
      <PageHeader
        title={`${author.nickname}님의 고민`}
        hideTitle
        backHref="/home"
        backLabel="마음 이야기로 돌아가기"
        className="-my-2"
      />
      <article aria-label="고민 글" className="flex flex-col gap-5">
        {/* 내 글이 우려나 위기로 판정됐거나 숨겨졌을 때만 보인다(005 US1). 화면 맨 위에 둔다. */}
        <SafetyNotice
          safety={detail.safety}
          target="post"
          action={
            // 숨겨진 내 글은 다시 살펴봐 달라고 한 번 요청할 수 있다(005 US4-AC8).
            <RequestReviewButton
              targetType="POST"
              targetId={detail.postId}
              requested={detail.safety?.reviewRequested ?? false}
              onRequested={() => {
                void queryClient.invalidateQueries({
                  queryKey: QUERY_KEYS.postDetail(detail.postId),
                  exact: true,
                });
              }}
            />
          }
        />

        {/*
          읽는 순서: 무대(몬스터와 HP, 공감) → 작성자와 본문 → 댓글.
          공감 버튼을 HP 막대 바로 아래에 두어 누르면 HP가 줄어드는 것이 함께 보인다.
        */}
        <MonsterStage
          emotion={monster?.emotion}
          className="rounded-surface flex flex-col gap-4 p-5"
        >
          <div role="group" aria-label="몬스터">
            {monster ? (
              <MonsterDisplay monster={monster} variant="detail" />
            ) : (
              <p className="text-body-sm text-fg-subtle py-6 text-center">
                분석 중
              </p>
            )}
            {/* "분석 중"은 몬스터가 오면 통째로 사라져 읽히지 않는다. 늘 있는 자리에 글자를 채운다. */}
            <p role="status" className="sr-only">
              {openedWithoutMonster && monster
                ? `${emotionLabel(monster.emotion)} 몬스터가 나타났어요.`
                : null}
            </p>
          </div>
          <div
            role="group"
            aria-label="공감과 댓글 수"
            className="text-body-sm text-fg-muted flex items-center gap-4 font-medium tabular-nums"
          >
            {detail.mine ? (
              <span>공감 {detail.likeCount}</span>
            ) : (
              <PostLikeButton detail={detail} />
            )}
            <span className="shrink-0">댓글 {detail.commentCount}</span>
          </div>
        </MonsterStage>

        <div className="flex flex-col gap-3">
          <header className="flex items-start justify-between gap-2">
            <div className="flex min-w-0 flex-col">
              <p className="text-body-sm text-fg font-bold break-words">
                {author.nickname}
              </p>
              {author.ai ? (
                // AI 친구에게는 직군과 경력이 없다. 그 자리에 AI 친구임을 글자로 보인다(012 US4-AC2).
                <AiBadge className="mt-1 self-start" />
              ) : (
                <p className="text-label text-fg-subtle">
                  {authorMetaLabel(author)}
                </p>
              )}
            </div>
            {detail.mine ? (
              <div className="flex shrink-0 items-center gap-1">
                <Button asChild variant="ghost" size="sm">
                  <Link href={`/post/${detail.postId}/edit`}>수정</Link>
                </Button>
                <DeletePostButton postId={detail.postId} />
              </div>
            ) : (
              // 다른 회원의 글에만 신고를 둔다(005 US3-AC3).
              <ReportButton targetType="POST" targetId={detail.postId} />
            )}
          </header>
          {author.ai ? (
            // 사람의 고민인 줄 알고 읽지 않게 본문보다 먼저 알린다(012 FR-030).
            <p className="bg-surface-sunken text-body-sm text-fg-muted rounded-surface px-4 py-3 break-keep">
              AI 친구가 지어 쓴 이야기예요. 실제로 겪은 일이 아니에요.{" "}
              <Link
                href={AI_FRIENDS_PATH}
                className="text-fg inline-flex min-h-11 items-center font-bold underline underline-offset-4"
              >
                AI 친구 알아보기
              </Link>
            </p>
          ) : null}
          <p className="text-body-lg text-fg break-words whitespace-pre-wrap">
            {detail.content}
          </p>
        </div>

        {/* 본문과 댓글은 선이 아니라 옅은 면의 띠로 나눈다. 띠는 화면 옆 여백까지 닿는다. */}
        <div className="border-surface-sunken -mx-5 flex flex-col gap-4 border-t-8 px-5 pt-5 sm:mx-0 sm:px-0">
          <div className="flex flex-col gap-2">
            <h2 className="text-title text-fg font-bold">
              이 고민에 달린 댓글
            </h2>
            {/* 댓글을 쓰는 자리에서 글쓴이가 듣고 싶은 말투를 먼저 본다. */}
            <dl className="text-body-sm flex flex-wrap items-center gap-2">
              <dt className="text-fg-subtle">댓글 말투</dt>
              <dd className="bg-surface-sunken text-fg rounded-full px-2.5 py-0.5 font-bold">
                {COMMENT_TONE_LABELS[detail.commentTone]}
              </dd>
            </dl>
          </div>
          <PostComments postId={detail.postId} />
        </div>
      </article>

      {/* 추천은 이 글의 일부가 아니라 article 밖에 둔다(007). */}
      <SimilarPosts postId={detail.postId} />
    </div>
  );
}
