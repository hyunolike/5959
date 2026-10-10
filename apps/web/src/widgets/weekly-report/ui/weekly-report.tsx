"use client";

import Link from "next/link";

import { EMOTION_COLORS, EMOTION_LABELS } from "@/entities/monster";
import { SupportResourceList } from "@/entities/support-resource";
import {
  formatWeek,
  useWeeklyReportQuery,
  type WeeklyReport as WeeklyReportData,
} from "@/entities/weekly-report";
import { ApiError } from "@/shared/api";
import { Button, Card, Spinner } from "@/shared/ui";

/** 편지를 쓰는 동안의 안내(008 US2-AC2). */
export const LETTER_PENDING_NOTICE = "편지를 쓰고 있어요.";
/** 위기로 판정된 글이 있던 주에 AI 편지 대신 보이는 말(008 FR-011). 판정이나 글의 내용을 말하지 않는다. */
export const SUPPORT_LETTER =
  "지난 한 주, 마음이 많이 힘드셨죠. 혼자 견디지 않아도 돼요. 지금 이야기를 들어 줄 수 있는 곳이 있어요.";

function isNotFound(error: unknown): boolean {
  return (
    error instanceof ApiError && (error.status === 404 || error.status === 400)
  );
}

/**
 * 한 주의 리포트(008 US1, US2, US4-AC4): 기간, 편지, 감정별 글 수, 수치, 앞 주와 견준 변화.
 * 편지를 쓰는 중이면 `useWeeklyReportQuery`가 다시 받아 새로고침 없이 채운다(US2-AC3).
 */
export function WeeklyReport({ weekStart }: { weekStart: string }) {
  const { data, error, isPending } = useWeeklyReportQuery(weekStart);

  if (isPending) {
    return (
      <Card className="flex w-full items-center justify-center">
        <Spinner />
      </Card>
    );
  }
  if (isNotFound(error)) {
    return (
      <Card className="flex w-full flex-col items-center gap-3 text-center">
        <p role="alert" className="text-sm text-neutral-600">
          이 주의 리포트가 없어요.
        </p>
        <Button asChild variant="ghost" size="sm">
          <Link href="/my?tab=reports">내 리포트 보기</Link>
        </Button>
      </Card>
    );
  }
  if (!data) {
    return (
      <Card className="w-full text-center">
        <p role="alert" className="text-sm text-red-600">
          리포트를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      </Card>
    );
  }
  return <WeeklyReportContent report={data} />;
}

function WeeklyReportContent({ report }: { report: WeeklyReportData }) {
  return (
    <article aria-label="주간 리포트" className="flex w-full flex-col gap-4">
      <p className="text-sm text-neutral-600">
        {formatWeek(report.weekStart, report.weekEnd)}
      </p>
      <Letter report={report} />
      <Emotions report={report} />
      <Numbers report={report} />
      <Comparison report={report} />
    </article>
  );
}

/** 편지 자리. 상태마다 다르다: 쓰는 중, 편지, 정해 둔 문구와 도움받을 곳. 닫혔으면 그리지 않는다(US2-AC4). */
function Letter({ report }: { report: WeeklyReportData }) {
  switch (report.letterStatus) {
    case "DONE":
      return (
        <Card role="region" aria-label="편지" className="flex flex-col gap-2">
          <h2 className="text-sm font-semibold text-neutral-900">
            한 주를 돌아보며
          </h2>
          <p className="text-base whitespace-pre-wrap text-neutral-900">
            {report.letter}
          </p>
        </Card>
      );
    case "PENDING":
      return (
        <Card role="region" aria-label="편지">
          <p aria-live="polite" className="text-sm text-neutral-500">
            {LETTER_PENDING_NOTICE}
          </p>
        </Card>
      );
    case "SUPPORT":
      return (
        <Card
          role="region"
          aria-label="도움 안내"
          className="flex flex-col gap-3 border-amber-300 bg-amber-50"
        >
          <p className="text-sm font-semibold text-neutral-900">
            {SUPPORT_LETTER}
          </p>
          <p className="text-sm text-neutral-700">
            전화번호를 누르면 바로 연결돼요.
          </p>
          <SupportResourceList />
        </Card>
      );
    case "GIVEN_UP":
      return null;
  }
}

/**
 * 감정별 글 수(US1-AC3). 가장 많은 수를 가득 찬 막대로 삼는다. 막대는 장식이고 값은 이름과 숫자가 전한다.
 * 감정 5종을 받은 순서대로 모두 보이고 0인 감정도 남긴다.
 */
function Emotions({ report }: { report: WeeklyReportData }) {
  const max = Math.max(...report.emotionCounts.map((item) => item.count), 1);

  return (
    <Card className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-neutral-900">
          지난주의 감정
        </h2>
        <p className="text-xs text-neutral-600">
          {report.topEmotion
            ? `가장 많았던 감정 ${EMOTION_LABELS[report.topEmotion]}`
            : "감정 분석이 끝난 글이 없어요"}
        </p>
      </div>
      <ul aria-label="감정별 글 수" className="flex flex-col gap-2">
        {report.emotionCounts.map(({ emotion, count }) => (
          <li key={emotion} className="flex items-center gap-3 text-sm">
            <span className="w-16 shrink-0 text-neutral-800">
              {EMOTION_LABELS[emotion]}
            </span>
            <span
              aria-hidden
              className="h-2.5 flex-1 overflow-hidden rounded bg-neutral-100"
            >
              <span
                data-testid="emotion-bar"
                className="block h-full rounded"
                style={{
                  width: `${(count / max) * 100}%`,
                  backgroundColor: EMOTION_COLORS[emotion],
                }}
              />
            </span>
            <span className="w-10 shrink-0 text-right text-neutral-900 tabular-nums">
              {count}개
            </span>
          </li>
        ))}
      </ul>
      {report.unanalyzedCount > 0 ? (
        <p className="text-xs text-neutral-500">
          분석되지 않은 글 {report.unanalyzedCount}개
        </p>
      ) : null}
    </Card>
  );
}

function Numbers({ report }: { report: WeeklyReportData }) {
  const items = [
    ["쓴 글", report.postCount],
    ["처치된 몬스터", report.defeatedCount],
    ["받은 공감", report.receivedLikes],
    ["받은 댓글", report.receivedComments],
  ] as const;

  return (
    <Card>
      <dl aria-label="지난주의 수치" className="grid grid-cols-2 gap-4">
        {items.map(([label, value]) => (
          <div key={label} className="flex flex-col gap-1">
            <dt className="text-xs text-neutral-500">{label}</dt>
            <dd className="text-lg font-semibold text-neutral-900 tabular-nums">
              {value}
            </dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}

/** 바로 앞 주의 리포트가 있을 때만 보인다(US4-AC4). */
function Comparison({ report }: { report: WeeklyReportData }) {
  const { previous } = report;
  if (!previous) {
    return null;
  }
  const diff = report.postCount - previous.postCount;
  const posts =
    diff === 0
      ? "글 수는 앞 주와 같아요."
      : `앞 주보다 글이 ${Math.abs(diff)}개 ${diff > 0 ? "늘었어요" : "줄었어요"}.`;
  const emotion = emotionChange(previous.topEmotion, report.topEmotion);

  return (
    <Card role="region" aria-label="앞 주와 견주기">
      <ul className="flex flex-col gap-1 text-sm text-neutral-700">
        <li>{posts}</li>
        {emotion ? <li>{emotion}</li> : null}
      </ul>
    </Card>
  );
}

function emotionChange(
  before: WeeklyReportData["topEmotion"],
  now: WeeklyReportData["topEmotion"],
): string | null {
  if (!before || !now) {
    return null;
  }
  return before === now
    ? `가장 많은 감정은 앞 주와 같이 ${EMOTION_LABELS[now]}이에요.`
    : `가장 많은 감정이 ${EMOTION_LABELS[before]}에서 ${EMOTION_LABELS[now]}(으)로 바뀌었어요.`;
}
