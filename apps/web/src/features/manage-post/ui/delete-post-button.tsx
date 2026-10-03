"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { Button, ConfirmDialog } from "@/shared/ui";

import { useDeletePostMutation } from "../api/use-delete-post-mutation";
import { deleteErrorMessage } from "../model/manage-error";

/**
 * 글 삭제 버튼(US4-AC2). 화면 안 확인 대화상자를 거쳐 지우고 `/home`으로 간다.
 * 지운 글은 되돌릴 수 없고 몬스터의 HP도 그대로라 확인을 한 번 받는다.
 */
export function DeletePostButton({ postId }: { postId: number }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const mutation = useDeletePostMutation(postId);

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
            onSuccess: () => router.push("/home"),
          })
        }
      />
    </>
  );
}
