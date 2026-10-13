"use client";

import { CaretDown, SlidersHorizontal } from "@phosphor-icons/react";
import { useRef, useState } from "react";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import type { FeedFilter, FeedOrder } from "@/entities/post";
import { Button, Chip, SegmentedControl } from "@/shared/ui";

import {
  CAREER_YEARS,
  DEFAULT_FEED_FILTER,
  JOB_ROLES,
  toggleValue,
} from "../model/filter-params";

const ORDERS: readonly { value: FeedOrder; label: string }[] = [
  { value: "LATEST", label: "최신순" },
  { value: "POPULAR", label: "인기순" },
];

/** 정렬 토글과 직군, 경력 다중 선택(FR-011). 같은 종류 안에서는 하나라도 맞으면 통과한다. */
export function FeedControls({
  filter,
  onChange,
}: {
  filter: FeedFilter;
  onChange: (next: FeedFilter) => void;
}) {
  const filtered = filter.jobRoles.length > 0 || filter.careerYears.length > 0;
  // 여닫이는 쓰는 사람이 정한다. 마지막 필터를 껐다고 닫으면 초점이 있던 칩이 사라져 초점을 잃는다.
  // 주소로 필터가 들어온 때처럼 필터가 새로 생기면 열어 준다.
  const [open, setOpen] = useState(filtered);
  const [wasFiltered, setWasFiltered] = useState(filtered);
  if (filtered !== wasFiltered) {
    setWasFiltered(filtered);
    if (filtered) {
      setOpen(true);
    }
  }
  const summaryRef = useRef<HTMLElement>(null);

  return (
    // 정렬과 필터 여닫이를 한 줄에 둔다. 같은 격자 칸에 겹쳐 놓고 여닫이의 summary를 오른쪽으로 민다.
    // 펼친 칩은 여닫이 안이라 그 아래 전체 너비로 놓인다. 정렬은 여닫이의 빈 자리에 깔리지 않게 위로 올린다.
    <div className="grid grid-cols-1 pb-2">
      <SegmentedControl
        label="정렬"
        options={ORDERS}
        value={filter.order}
        onChange={(order) => onChange({ ...filter, order })}
        className="relative z-10 col-start-1 row-start-1 self-start justify-self-start"
      />
      <details
        open={open}
        onToggle={(event) => setOpen(event.currentTarget.open)}
        className="group col-start-1 row-start-1"
      >
        <summary
          ref={summaryRef}
          className="ogu-pressable rounded-control-sm bg-surface-sunken text-body-sm text-fg hover:bg-line relative ml-auto flex h-10 w-fit cursor-pointer list-none items-center gap-1.5 px-3.5 font-bold before:absolute before:inset-x-0 before:-inset-y-0.5 before:content-[''] [&::-webkit-details-marker]:hidden"
        >
          <SlidersHorizontal size={18} aria-hidden />
          필터
          {filtered
            ? ` (${filter.jobRoles.length + filter.careerYears.length})`
            : ""}
          <CaretDown
            size={14}
            aria-hidden
            className="transition-transform group-open:rotate-180 motion-reduce:transition-none"
          />
        </summary>
        <div className="mt-4 flex flex-col gap-3">
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
              className="-ml-3 self-start underline underline-offset-4"
              onClick={() => {
                onChange({ ...DEFAULT_FEED_FILTER, order: filter.order });
                // 이 버튼은 필터가 없어지면 사라진다. 초점을 여닫이로 옮겨 둔다.
                summaryRef.current?.focus();
              }}
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
      {/* 칩의 누르는 영역은 위아래로 2px씩 넓다. 줄 사이 8px이면 겹치지 않는다. */}
      <div className="flex flex-wrap gap-2">{children}</div>
    </div>
  );
}
