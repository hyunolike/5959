"use client";

import { useRouter } from "next/navigation";

import { usePostDetailQuery } from "@/entities/post";
import { updateErrorOf, useUpdatePostMutation } from "@/features/manage-post";
import { PostForm, type WritePostFormValues } from "@/features/write-post";
import { ApiError } from "@/shared/api";
import { Card, Spinner } from "@/shared/ui";

function Notice({ message }: { message: string }) {
  return (
    <p role="alert" className="text-center text-sm text-neutral-600">
      {message}
    </p>
  );
}

/**
 * 글 고치기(US4-AC1). 작성 폼(features/write-post의 `PostForm`)을 지금 본문과 말투로 채워
 * 보여 주고, 저장은 features/manage-post의 수정 뮤테이션으로 한다. 두 기능은 서로 모르므로
 * 이 위젯이 조립한다. 고치면 상세로 돌아간다. 몬스터는 바뀌지 않는다.
 */
export function PostEditor({ postId }: { postId: number }) {
  const router = useRouter();
  // 고치는 동안에는 몬스터를 보여 주지 않으므로 분석 중이어도 다시 부르지 않는다.
  const { data, error, isPending } = usePostDetailQuery(postId, {
    poll: false,
  });
  const mutation = useUpdatePostMutation(postId);

  if (isPending) {
    return (
      <div className="flex justify-center">
        <Spinner />
      </div>
    );
  }
  if (error instanceof ApiError && error.status < 500) {
    return <Notice message="삭제된 글이에요." />;
  }
  if (!data) {
    return (
      <Notice message="글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요." />
    );
  }
  if (!data.mine) {
    return <Notice message="내 글만 고칠 수 있어요." />;
  }

  const onSubmit = async (values: WritePostFormValues) => {
    try {
      await mutation.mutateAsync(values);
      router.push(`/post/${postId}`);
    } catch (failure) {
      return updateErrorOf(failure);
    }
  };

  return (
    <PostForm
      defaultValues={{ content: data.content, commentTone: data.commentTone }}
      submitLabel="고치기"
      locked={mutation.isSuccess}
      onSubmit={onSubmit}
    />
  );
}

/** 고치기 화면 틀. 페이지는 이 카드만 그린다. */
export function PostEditorCard({ postId }: { postId: number }) {
  return (
    <Card className="w-full max-w-xl">
      <h1 className="text-xl font-semibold text-neutral-900">고민 고치기</h1>
      <p className="mt-1 text-sm text-neutral-500">
        본문과 댓글 말투를 고쳐도 몬스터는 그대로예요.
      </p>
      <div className="mt-6">
        <PostEditor postId={postId} />
      </div>
    </Card>
  );
}
