"use client";

import { useState } from "react";

import {
  Button,
  ConfirmDialog,
  SegmentedControl,
  Toaster,
  useToasts,
} from "@/shared/ui";

/** 고른 값을 기억해야 하는 세그먼트를 카탈로그에서 눌러 보게 한다. */
export function CatalogSegmented() {
  const [order, setOrder] = useState<"LATEST" | "POPULAR">("LATEST");
  return (
    <SegmentedControl
      label="정렬 예시"
      options={[
        { value: "LATEST", label: "최신순" },
        { value: "POPULAR", label: "인기순" },
      ]}
      value={order}
      onChange={setOrder}
      className="self-start"
    />
  );
}

/** 열어야 보이는 컴포넌트(대화상자, 토스트)를 카탈로그에서 띄워 보는 버튼. */
export function CatalogOverlays() {
  const [open, setOpen] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);
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
      <Button variant="secondary" onClick={() => setSheetOpen(true)}>
        시트 열기
      </Button>
      <ConfirmDialog
        open={sheetOpen}
        role="dialog"
        presentation="sheet"
        title="로그인이 필요해요"
        description="글 전체를 읽으려면 로그인해 주세요."
        confirmLabel="로그인하기"
        cancelLabel="계속 둘러보기"
        confirmVariant="primary"
        onConfirm={() => setSheetOpen(false)}
        onCancel={() => setSheetOpen(false)}
      />
      <Toaster toasts={toasts} onDismiss={dismiss} />
    </div>
  );
}
