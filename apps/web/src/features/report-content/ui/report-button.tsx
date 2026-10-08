"use client";

import { useId, useState } from "react";

import { ApiError } from "@/shared/api";
import { countGraphemes } from "@/shared/lib";
import { ConfirmDialog } from "@/shared/ui";

import { useReportMutation } from "../api/use-report-mutation";
import {
  REPORT_DETAIL_MAX_LENGTH,
  REPORT_REASONS,
  type ReportReason,
  type ReportTargetType,
} from "../model/reason";

const TARGET_LABEL: Record<ReportTargetType, string> = {
  POST: "글",
  COMMENT: "댓글",
};

/**
 * 글이나 댓글을 신고하는 버튼과 대화상자(005 US3). 다른 회원의 것에만 둔다(내 것에는 부르는 쪽이 그리지 않는다).
 *
 * - 사유 넷 가운데 하나를 고르고, 기타면 설명을 200자까지 적는다(US3-AC1).
 * - 접수되면 "신고가 접수됐어요", 이미 신고했으면 "이미 신고한 글이에요"를 버튼 자리에 보인다(US3-AC2).
 *   둘 다 대화상자를 닫는다. 그 밖의 실패는 대화상자 안에 보인다.
 * - 신고했다는 것은 이 화면에만 남는다. 서버는 누가 신고했는지를 어디에도 싣지 않는다.
 */
export function ReportButton({
  targetType,
  targetId,
  className,
}: {
  targetType: ReportTargetType;
  targetId: number;
  className?: string;
}) {
  const groupName = useId();
  const detailId = useId();
  const report = useReportMutation();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [detail, setDetail] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const target = TARGET_LABEL[targetType];
  const detailLength = countGraphemes(detail.trim());
  const detailTooLong = detailLength > REPORT_DETAIL_MAX_LENGTH;

  const close = () => {
    setOpen(false);
    setReason(null);
    setDetail("");
    setError(null);
  };

  const submit = () => {
    if (reason === null) {
      setError("신고 사유를 골라 주세요.");
      return;
    }
    if (reason === "OTHER" && detailTooLong) {
      setError(`설명은 ${REPORT_DETAIL_MAX_LENGTH}자까지 쓸 수 있어요.`);
      return;
    }
    setError(null);
    report.mutate(
      {
        targetType,
        targetId,
        reason,
        ...(reason === "OTHER" && detail.trim()
          ? { detail: detail.trim() }
          : {}),
      },
      {
        onSuccess: () => {
          setNotice("신고가 접수됐어요");
          close();
        },
        onError: (cause) => {
          if (cause instanceof ApiError && cause.code === "ALREADY_REPORTED") {
            setNotice(`이미 신고한 ${target}이에요`);
            close();
            return;
          }
          setError(
            cause instanceof ApiError && cause.code === "REPORT_RATE_LIMITED"
              ? "신고를 너무 자주 보내고 있어요. 잠시 후 다시 시도해 주세요."
              : "신고하지 못했어요. 잠시 후 다시 시도해 주세요.",
          );
        },
      },
    );
  };

  if (notice !== null) {
    return (
      <span role="status" className="text-xs text-neutral-500">
        {notice}
      </span>
    );
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={
          className ??
          "rounded px-1 text-xs text-neutral-500 hover:text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
        }
      >
        신고
      </button>
      <ConfirmDialog
        open={open}
        title={`이 ${target}을 신고할까요?`}
        description="신고한 사람은 글쓴이에게 알려지지 않아요."
        confirmLabel="신고하기"
        pending={report.isPending}
        error={error}
        onConfirm={submit}
        onCancel={close}
      >
        <fieldset className="flex flex-col gap-2">
          <legend className="sr-only">신고 사유</legend>
          {REPORT_REASONS.map((option) => (
            <label
              key={option.value}
              className="flex items-center gap-2 text-sm text-neutral-900"
            >
              <input
                type="radio"
                name={groupName}
                value={option.value}
                checked={reason === option.value}
                onChange={() => {
                  setReason(option.value);
                  setError(null);
                }}
              />
              {option.label}
            </label>
          ))}
        </fieldset>
        {reason === "OTHER" ? (
          <div className="flex flex-col gap-1">
            <label htmlFor={detailId} className="text-sm text-neutral-700">
              어떤 점이 문제인지 알려 주세요 (선택)
            </label>
            <textarea
              id={detailId}
              rows={3}
              value={detail}
              onChange={(event) => setDetail(event.target.value)}
              className="w-full rounded-md border border-neutral-300 px-3 py-2 text-sm text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
            />
            <p
              className={
                detailTooLong
                  ? "text-right text-xs text-red-600"
                  : "text-right text-xs text-neutral-500"
              }
            >
              {detailLength}/{REPORT_DETAIL_MAX_LENGTH}
            </p>
          </div>
        ) : null}
      </ConfirmDialog>
    </>
  );
}
