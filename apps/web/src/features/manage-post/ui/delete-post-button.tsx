"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { Button, ConfirmDialog } from "@/shared/ui";

import { useDeletePostMutation } from "../api/use-delete-post-mutation";
import { deleteErrorMessage } from "../model/manage-error";

/**
 * 글 삭제 버튼(US4-AC2). 화면 안 확인 대화상자를 거쳐 지우고 `/home`으로 간다.
 * 지운 글은 되돌릴 수 없고 몬스터의 HP도 그대로라 확인을 한 번 받는다. 상세 캐시는 이 버튼이
 * 화면에서 사라질 때(상세를 떠날 때) 지운다. 그 전에 지우면 상세가 404를 다시 불러와 깜박인다.
 */
export function DeletePostButton({ postId }: { postId: number }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const mutation = useDeletePostMutation(postId);
  const deletedRef = useRef(false);
  const { forgetDeletedPost } = mutation;

  useEffect(
    () => () => {
      if (deletedRef.current) {
        forgetDeletedPost();
      }
    },
    [forgetDeletedPost],
  );

  const close = () => {
    setOpen(false);
    mutation.reset();
  };

  return (
    <>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        onClick={() => setOpen(true)}
      >
        삭제
      </Button>
      <ConfirmDialog
        open={open}
        title="글을 지울까요?"
        description="지운 글은 되돌릴 수 없어요."
        confirmLabel="삭제하기"
        pending={mutation.isPending || mutation.isSuccess}
        error={mutation.isError ? deleteErrorMessage(mutation.error) : null}
        onCancel={close}
        onConfirm={() =>
          mutation.mutate(undefined, {
            onSuccess: () => {
              deletedRef.current = true;
              router.push("/home");
            },
          })
        }
      />
    </>
  );
}
