import {
  EMOTION_COLORS,
  type EmotionLabels,
  type EmotionShare,
} from "../model/types";

/**
 * 감정 5종의 분포(US4-AC1): 비율만큼 나눈 막대 하나와, 감정마다 이름, 수, 비율을 적은 범례.
 * 범례는 받은 순서(감정 5종 고정 순서)대로 모두 보이고 0인 감정도 남긴다. 막대는 장식이고 값은 범례가 전한다.
 */
export function DistributionBar({
  distribution,
  labels,
}: {
  distribution: readonly EmotionShare[];
  labels: EmotionLabels;
}) {
  const shown = distribution.filter((share) => share.percent > 0);

  return (
    <div className="flex flex-col gap-3">
      <div
        aria-hidden
        className="flex h-3 w-full gap-0.5 overflow-hidden rounded bg-neutral-100"
      >
        {shown.map((share) => (
          <div
            key={share.emotion}
            data-testid="distribution-segment"
            title={`${labels[share.emotion]} ${share.percent}%`}
            style={{
              width: `${share.percent}%`,
              backgroundColor: EMOTION_COLORS[share.emotion],
            }}
          />
        ))}
      </div>
      <ul
        aria-label="감정 분포"
        className="grid grid-cols-1 gap-x-4 gap-y-1 text-sm sm:grid-cols-2"
      >
        {distribution.map((share) => (
          <li key={share.emotion} className="flex items-center gap-2">
            <span
              aria-hidden
              className="size-2.5 shrink-0 rounded-sm"
              style={{ backgroundColor: EMOTION_COLORS[share.emotion] }}
            />
            <span className="text-neutral-800">{labels[share.emotion]}</span>
            <span className="ml-auto text-neutral-500 tabular-nums">
              {share.count}마리
            </span>
            <span className="w-10 text-right font-medium text-neutral-900 tabular-nums">
              {share.percent}%
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
