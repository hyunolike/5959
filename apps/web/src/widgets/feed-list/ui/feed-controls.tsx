"use client";

import { SlidersHorizontal } from "lucide-react";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import type { FeedFilter, FeedOrder } from "@/entities/post";
import { cn } from "@/shared/lib";

import {
  CAREER_YEARS,
  DEFAULT_FEED_FILTER,
  JOB_ROLES,
  toggleValue,
} from "../model/filter-params";

const ORDER_LABELS: Record<FeedOrder, string> = {
  LATEST: "최신순",
  POPULAR: "인기순",
};

/** 정렬 토글과 직군, 경력 다중 선택(FR-011). 같은 종류 안에서는 하나라도 맞으면 통과한다. */
export function FeedControls({
  filter,
  onChange,
}: {
  filter: FeedFilter;
  onChange: (next: FeedFilter) => void;
}) {
  const filtered = filter.jobRoles.length > 0 || filter.careerYears.length > 0;

  return (
    <div className="flex flex-col gap-3">
      <div role="group" aria-label="정렬" className="flex gap-2">
        {(Object.keys(ORDER_LABELS) as FeedOrder[]).map((order) => (
          <Chip
            key={order}
            pressed={filter.order === order}
            onClick={() => onChange({ ...filter, order })}
          >
            {ORDER_LABELS[order]}
          </Chip>
        ))}
      </div>
      <details
        open={filtered}
        className="rounded-lg border border-neutral-200 bg-white p-3"
      >
        <summary className="focus-visible:ring-brand-600 flex min-h-10 cursor-pointer list-none items-center gap-2 rounded-lg text-sm font-medium text-neutral-700 focus-visible:ring-2 focus-visible:outline-none">
          <SlidersHorizontal size={16} aria-hidden />
          필터
          {filtered
            ? ` (${filter.jobRoles.length + filter.careerYears.length})`
            : ""}
        </summary>
        <div className="mt-3 flex flex-col gap-3">
          <ChipGroup label="직군">
            {JOB_ROLES.map((jobRole) => (
              <Chip
                key={jobRole}
                pressed={filter.jobRoles.includes(jobRole)}
                onClick={() =>
                  onChange({
                    ...filter,
                    jobRoles: toggleValue(filter.jobRoles, jobRole),
                  })
                }
              >
                {JOB_ROLE_LABELS[jobRole]}
              </Chip>
            ))}
          </ChipGroup>
          <ChipGroup label="경력">
            {CAREER_YEARS.map((careerYear) => (
              <Chip
                key={careerYear}
                pressed={filter.careerYears.includes(careerYear)}
                onClick={() =>
                  onChange({
                    ...filter,
                    careerYears: toggleValue(filter.careerYears, careerYear),
                  })
                }
              >
                {CAREER_YEAR_LABELS[careerYear]}
              </Chip>
            ))}
          </ChipGroup>
          {filtered ? (
            <button
              type="button"
              className="self-start text-xs text-neutral-500 underline"
              onClick={() =>
                onChange({ ...DEFAULT_FEED_FILTER, order: filter.order })
              }
            >
              필터 지우기
            </button>
          ) : null}
        </div>
      </details>
    </div>
  );
}

function ChipGroup({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-1.5">
      <span className="text-xs text-neutral-500">{label}</span>
      <div className="flex flex-wrap gap-1.5">{children}</div>
    </div>
  );
}

function Chip({
  pressed,
  onClick,
  children,
}: {
  pressed: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={cn(
        "focus-visible:ring-brand-600 min-h-9 rounded-full border px-3.5 py-1.5 text-xs font-medium transition-colors focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none",
        pressed
          ? "border-brand-600 bg-brand-600 text-white"
          : "hover:border-brand-200 hover:bg-brand-50 border-neutral-200 bg-white text-neutral-600",
      )}
    >
      {children}
    </button>
  );
}
