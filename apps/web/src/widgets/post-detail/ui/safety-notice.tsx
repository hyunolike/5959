"use client";

import { useState, type ReactNode } from "react";

import { HIDDEN_FROM_OTHERS_LABEL } from "@/entities/post";
import { SupportResourceList } from "@/entities/support-resource";
import type { components } from "@/shared/api";
import { cn } from "@/shared/lib";
import { Card } from "@/shared/ui";

export type ContentSafety = components["schemas"]["ContentSafety"];

/** 안내가 필요한가: 우려나 위기로 판정됐거나 숨겨졌다. */
export function needsSafetyNotice(safety: ContentSafety | undefined): boolean {
  return safety !== undefined && (safety.level !== "NONE" || safety.hidden);
}

/**
 * 작성자에게만 보이는 도움 안내(005 US1). 응답의 `safety`가 있을 때만 그린다.
 *
 * - 위기이거나 숨겨졌으면 닫을 수 없는 안내다. 숨겨졌으면 다른 회원에게 보이지 않는다는 설명을 함께 보인다(US1-AC1, AC3).
 * - 우려면 접을 수 있는 안내다(US1-AC4).
 * - 단계 이름("위기", "우려")은 화면에 쓰지 않는다. 판정을 통보하는 것이 아니라 도움받을 곳을 알리는 자리다.
 * - 전화번호는 누르면 전화 앱으로 넘어간다.
 */
export function SafetyNotice({
  safety,
  target,
  action,
}: {
  safety: ContentSafety | undefined;
  /** 안내가 가리키는 것. 숨김 설명의 말이 달라진다. */
  target: "post" | "comment";
  /** 숨겨졌을 때 설명 아래에 둘 것(재검토 요청 버튼). */
  action?: ReactNode;
}) {
  const shown = needsSafetyNotice(safety);
  const urgent = safety?.level === "CRISIS" || safety?.hidden === true;
  const [open, setOpen] = useState(true);

  if (!shown || safety === undefined) {
    return null;
  }

  return (
    <Card
      role="region"
      aria-label="도움 안내"
      className={cn(
        "flex flex-col gap-3",
        urgent ? "border-amber-300 bg-amber-50" : "border-neutral-200",
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <p className="text-sm font-semibold text-neutral-900">
          마음이 많이 힘드신가요? 혼자 견디지 않아도 돼요.
        </p>
        {urgent ? null : (
          <button
            type="button"
            aria-expanded={open}
            onClick={() => setOpen((value) => !value)}
            className="shrink-0 rounded px-1 text-xs text-neutral-600 hover:text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
          >
            {open ? "접기" : "도움받을 곳 보기"}
          </button>
        )}
      </div>
      {safety.hidden ? (
        <p className="text-sm text-neutral-700">
          <strong className="font-medium">
            {target === "post"
              ? `이 글은 ${HIDDEN_FROM_OTHERS_LABEL}.`
              : "내 댓글 가운데 다른 회원에게 보이지 않는 것이 있어요."}
          </strong>{" "}
          나에게는 그대로 보여요.
        </p>
      ) : null}
      {open || urgent ? (
        <>
          <p className="text-sm text-neutral-700">
            지금 이야기를 들어 줄 수 있는 곳이에요. 전화번호를 누르면 바로
            연결돼요.
          </p>
          <SupportResourceList />
        </>
      ) : null}
      {safety.hidden && action ? <div>{action}</div> : null}
    </Card>
  );
}
