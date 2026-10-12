"use client";

import { useState } from "react";

import { Button, ConfirmDialog, Toaster, useToasts } from "@/shared/ui";

/** 열어야 보이는 컴포넌트(대화상자, 토스트)를 카탈로그에서 띄워 보는 버튼. */
export function CatalogOverlays() {
  const [open, setOpen] = useState(false);
  const { toasts, show, dismiss } = useToasts();

  return (
    <div className="flex flex-wrap gap-2">
      <Button variant="secondary" onClick={() => setOpen(true)}>
        대화상자 열기
      </Button>
      <Button
        variant="secondary"
        onClick={() => show({ message: "새 댓글이 달렸어요" })}
      >
        토스트 띄우기
      </Button>
      <ConfirmDialog
        open={open}
        title="글을 지울까요?"
        description="지운 글은 되돌릴 수 없어요."
        confirmLabel="지우기"
        onConfirm={() => setOpen(false)}
        onCancel={() => setOpen(false)}
      />
      <Toaster toasts={toasts} onDismiss={dismiss} />
    </div>
  );
}
