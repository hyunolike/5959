"use client";

import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import { MonsterDisplay } from "@/entities/monster";
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

/** 없거나 지운 글(404), 숫자가 아닌 글 ID(400). */
export function PostNotFound() {
  return (
    <Card className="w-full max-w-xl text-center">
      <p role="alert" className="text-sm text-neutral-600">
        {DELETED_POST_NOTICE}
      </p>
    </Card>
  );
}

function isNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError && (error.status === 404 || error.status === 400)
  );
}

/**
 * 글 상세(FR-012): 작성자, 본문, 댓글 말투, 몬스터(3D 또는 정지 이미지, US5), 공감과 댓글.
 * 몬스터가 생기기 전에는 "분석 중"을 보여 주고, `usePostDetailQuery`가 몬스터가
 * 생길 때까지 다시 불러와 새로고침 없이 바꿔 그린다(FR-015, US1-AC3).
 * 공감과 댓글은 응답 전에 HP를 줄여 보여 주고 몬스터 자리가 맞는 반응을 한다(US3-AC10).
 * 내 글이면 고치기 화면으로 가는 링크와 삭제 버튼을 둔다(US4-AC1, AC2).
 */
export function PostDetail({ postId }: { postId: number }) {
  const { data, error, isPending } = usePostDetailQuery(postId);

  if (isPending) {
    return (
      <Card className="flex w-full max-w-xl items-center justify-center">
        <Spinner />
      </Card>
    );
  }

  if (isNotFound(error)) {
    return <PostNotFound />;
  }

  if (!data) {
    return (
      <Card className="w-full max-w-xl text-center">
        <p role="alert" className="text-sm text-red-600">
          글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      </Card>
    );
  }

  return <PostDetailContent detail={data} />;
}

function PostDetailContent({ detail }: { detail: PostDetailData }) {
  const { author, monster } = detail;
  const queryClient = useQueryClient();

  return (
    <div className="flex w-full max-w-xl flex-col gap-4">
      <article aria-label="고민 글" className="flex flex-col gap-4">
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
        <Card aria-label="몬스터" className="flex flex-col gap-2">
          {monster ? (
            <MonsterDisplay
              monster={monster}
              variant="detail"
              resetKey={detail.postId}
            />
          ) : (
            <p
              aria-live="polite"
              className="py-4 text-center text-sm text-neutral-500"
            >
              분석 중
            </p>
          )}
        </Card>

        <Card className="flex flex-col gap-4">
          <header className="flex items-start justify-between gap-2">
            <div className="flex flex-col gap-0.5">
              <p className="text-sm font-semibold text-neutral-900">
                {author.nickname}
              </p>
              <p className="text-xs text-neutral-500">
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
          <p className="text-base whitespace-pre-wrap text-neutral-900">
            {detail.content}
          </p>
          <dl className="flex items-center gap-2 text-xs">
            <dt className="text-neutral-500">댓글 말투</dt>
            <dd className="rounded-full bg-neutral-100 px-2 py-0.5 text-neutral-700">
              {COMMENT_TONE_LABELS[detail.commentTone]}
            </dd>
          </dl>
          <div
            role="group"
            aria-label="공감과 댓글 수"
            className="flex items-center gap-3 text-xs text-neutral-600 tabular-nums"
          >
            {detail.mine ? (
              <span>공감 {detail.likeCount}</span>
            ) : (
              <PostLikeButton detail={detail} />
            )}
            <span>댓글 {detail.commentCount}</span>
          </div>
        </Card>

        <Card>
          <PostComments postId={detail.postId} />
        </Card>
      </article>

      {/* 추천은 이 글의 일부가 아니라 article 밖에 둔다(007). */}
      <SimilarPosts postId={detail.postId} />
    </div>
  );
}
