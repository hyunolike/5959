import { CaretDown } from "@phosphor-icons/react/dist/ssr";

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
              <div className="border-line flex w-full flex-1 flex-col items-center justify-end gap-1 border-b">
                {total > 0 ? (
                  <span className="text-caption text-fg-subtle tabular-nums">
                    {total}
                  </span>
                ) : null}
                <div
                  data-testid="weekly-bar"
                  className="flex w-full max-w-6 flex-col-reverse gap-0.5 overflow-hidden rounded-t-[4px]"
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
              <span className="text-caption text-fg-subtle tabular-nums">
                {weekLabel(week)}
              </span>
            </li>
          );
        })}
      </ol>
      {/*
        막대의 색만으로는 감정별 수를 읽기 어렵고(밝은 색은 바탕과 3:1에 못 미친다) title은 마우스에서만 보인다.
        터치와 저시력 사용자가 값을 볼 수 있게 주별 수를 글자로 펼쳐 볼 수 있게 한다.
      */}
      <details className="group mt-3">
        {/* summary를 flex로 두면 기본 삼각형이 사라진다. 누를 수 있다는 표시를 화살표와 밑줄로 준다. */}
        <summary className="rounded-inline text-label text-fg-muted inline-flex min-h-11 cursor-pointer list-none items-center gap-1 font-medium underline underline-offset-4">
          주별 수 보기
          <CaretDown
            size={14}
            aria-hidden
            className="transition-transform group-open:rotate-180 motion-reduce:transition-none"
          />
        </summary>
        <ul className="text-label text-fg-muted mt-1 flex flex-col gap-1 tabular-nums">
          {weekly.map((week) => (
            <li key={week.weekStart}>{weekSummary(week, labels)}</li>
          ))}
        </ul>
      </details>
      {/* 표의 최소 콘텐츠 폭이 숨김 영역 밖에서 페이지를 넓히지 않도록 컨테이너를 자른다. */}
      <div className="sr-only">
        <table>
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
    </div>
  );
}
