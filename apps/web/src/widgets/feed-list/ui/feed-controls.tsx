"use client";

import { SlidersHorizontal } from "@phosphor-icons/react";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import type { FeedFilter, FeedOrder } from "@/entities/post";
import { Button, Chip } from "@/shared/ui";

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
    <div className="flex flex-col gap-2">
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
      <details open={filtered} className="pb-2">
        <summary className="rounded-inline text-label text-fg-muted flex min-h-11 cursor-pointer list-none items-center gap-2 font-medium">
          <SlidersHorizontal size={18} aria-hidden />
          필터
          {filtered
            ? ` (${filter.jobRoles.length + filter.careerYears.length})`
            : ""}
        </summary>
        <div className="mt-2 flex flex-col gap-3">
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
            <Button
              variant="ghost"
              size="sm"
              className="self-start underline underline-offset-4"
              onClick={() =>
                onChange({ ...DEFAULT_FEED_FILTER, order: filter.order })
              }
            >
              필터 지우기
            </Button>
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
    <div role="group" aria-label={label} className="flex flex-col gap-2">
      <span className="text-caption text-fg-subtle">{label}</span>
      {/* 칩의 누르는 영역이 위아래로 5px씩 넓어서, 줄 사이를 12px로 둬 겹치지 않게 한다. */}
      <div className="flex flex-wrap gap-x-2 gap-y-3">{children}</div>
    </div>
  );
}
