import { formatDate } from "@/shared/lib";

import {
  EMOTION_COLORS,
  type EmotionLabels,
  type WeeklyEmotionCount,
} from "../model/types";

const weekTotal = (week: WeeklyEmotionCount) =>
  week.counts.reduce((sum, item) => sum + item.count, 0);

const weekLabel = (week: WeeklyEmotionCount) =>
  formatDate(week.weekStart, "M.DD");

/** "10.05 주: 불안 1, 짜증 2". 몬스터가 없던 주는 "10.05 주: 없음". */
function weekSummary(week: WeeklyEmotionCount, labels: EmotionLabels): string {
  const parts = week.counts
    .filter((item) => item.count > 0)
    .map((item) => `${labels[item.emotion]} ${item.count}`);
  return `${weekLabel(week)} 주: ${parts.length > 0 ? parts.join(", ") : "없음"}`;
}

/**
 * 최근 8주의 감정별 몬스터 수(US4-AC3). 주마다 막대 하나이고 감정별로 쌓는다. 높이는 가장 많은 주를
 * 기준으로 하고, 몬스터가 없던 주는 높이가 0이다. 주의 이름은 한국 시간 월요일 날짜다.
 * 같은 값을 화면 낭독기가 읽을 표로도 둔다. 색 범례는 분포 막대의 범례와 같아 여기서 되풀이하지 않는다.
 */
export function WeeklyChart({
  weekly,
  labels,
}: {
  weekly: readonly WeeklyEmotionCount[];
  labels: EmotionLabels;
}) {
  const max = Math.max(1, ...weekly.map(weekTotal));

  return (
    <div>
      <ol aria-hidden className="flex h-32 items-stretch gap-2">
        {weekly.map((week) => {
          const total = weekTotal(week);
          return (
            <li
              key={week.weekStart}
              title={weekSummary(week, labels)}
              className="flex min-w-0 flex-1 flex-col items-center gap-1"
            >
              <div className="flex w-full flex-1 flex-col items-center justify-end gap-1 border-b border-neutral-200">
                {total > 0 ? (
                  <span className="text-xs text-neutral-600 tabular-nums">
                    {total}
                  </span>
                ) : null}
                <div
                  data-testid="weekly-bar"
                  className="flex w-full max-w-6 flex-col-reverse gap-0.5 overflow-hidden rounded-t"
                  style={{ height: `${(total / max) * 75}%` }}
                >
                  {week.counts
                    .filter((item) => item.count > 0)
                    .map((item) => (
                      <div
                        key={item.emotion}
                        style={{
                          flexGrow: item.count,
                          backgroundColor: EMOTION_COLORS[item.emotion],
                        }}
                      />
                    ))}
                </div>
              </div>
              <span className="text-xs text-neutral-500 tabular-nums">
                {weekLabel(week)}
              </span>
            </li>
          );
        })}
      </ol>
      <table className="sr-only">
        <caption>최근 8주 감정별 몬스터 수</caption>
        <thead>
          <tr>
            <th scope="col">주 시작일</th>
            {weekly[0]?.counts.map((item) => (
              <th key={item.emotion} scope="col">
                {labels[item.emotion]}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {weekly.map((week) => (
            <tr key={week.weekStart}>
              <th scope="row">{week.weekStart}</th>
              {week.counts.map((item) => (
                <td key={item.emotion}>{item.count}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
