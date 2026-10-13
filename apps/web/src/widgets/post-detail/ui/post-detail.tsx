"use client";

import { useQueryClient } from "@tanstack/react-query";
import { CaretLeft } from "@phosphor-icons/react";
import Image from "next/image";
import Link from "next/link";
import { useState } from "react";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
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
import { Button, Card, Spinner } from "@/shared/ui";

import { PostComments } from "./post-comments";
import { SafetyNotice } from "./safety-notice";
import { SimilarPosts } from "./similar-posts";

function BackToFeed() {
  return (
    <Link
      href="/home"
      className="text-label text-fg-subtle hover:text-fg rounded-inline -ml-1 inline-flex min-h-11 items-center gap-1 self-start font-medium"
    >
      <CaretLeft size={18} aria-hidden />
      마음 이야기
    </Link>
  );
}

/** 글을 보여 주지 못할 때의 안내. 오구와 함께 돌아갈 곳을 준다. */
function Unavailable({
  message,
  tone,
}: {
  message: string;
  tone: "neutral" | "error";
}) {
  return (
    <Card className="w-full max-w-xl py-10 text-center sm:py-10">
      <h1 className="sr-only">고민 글</h1>
      <Image
        src="/brand/ogu-companion.png"
        alt=""
        width={96}
        height={79}
        className="mx-auto mb-4 h-auto w-16 object-contain"
      />
      <p
        role="alert"
        className={
          tone === "error"
            ? "text-body-sm text-danger"
            : "text-body-sm text-fg-subtle"
        }
      >
        {message}
      </p>
      <Button asChild variant="secondary" className="mt-5">
        <Link href="/home">마음 이야기로 돌아가기</Link>
      </Button>
    </Card>
  );
}

/** 없거나 지운 글(404), 숫자가 아닌 글 ID(400). */
export function PostNotFound() {
  return <Unavailable message={DELETED_POST_NOTICE} tone="neutral" />;
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
      <Card className="flex w-full max-w-xl items-center justify-center py-12 sm:py-12">
        <Spinner label="글을 불러오는 중" />
      </Card>
    );
  }

  if (isNotFound(error)) {
    return <PostNotFound />;
  }

  if (!data) {
    return (
      <Unavailable
        message="글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요."
        tone="error"
      />
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
      <BackToFeed />
      <article aria-label="고민 글" className="flex flex-col gap-5">
        {/* 무대가 본문보다 먼저 와서, 화면 낭독기로는 누구의 글인지부터 듣게 한다. */}
        <h1 className="sr-only">{author.nickname}님의 고민</h1>
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
              <p className="text-label text-fg-subtle">
                {`${JOB_ROLE_LABELS[author.jobRole]} · ${CAREER_YEAR_LABELS[author.careerYear]}`}
              </p>
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
          <p className="text-body-lg text-fg break-words whitespace-pre-wrap">
            {detail.content}
          </p>
        </div>

        <div className="border-line flex flex-col gap-4 border-t pt-5">
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
