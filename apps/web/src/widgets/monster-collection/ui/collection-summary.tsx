import {
  emotionLabel,
  type EmotionType,
  type MonsterCardSummary,
} from "@/entities/monster";
import { Chip } from "@/shared/ui";

/**
 * 도감의 요약(010 US4). 모은 수와 다섯 감정마다의 장수를 보이고, 감정을 눌러 그 감정의 카드만 본다.
 * 한 장도 없는 감정은 누를 수 없는 빈 자리로 두고, 어떤 몬스터를 아직 못 만났는지 글로도 적는다.
 * 숫자는 감정으로 거르기 전의 전체라 감정을 골라도 바뀌지 않는다.
 */
export function CollectionSummary({
  summary,
  selected,
  onSelect,
}: {
  summary: MonsterCardSummary;
  selected: EmotionType | null;
  onSelect: (emotion: EmotionType | null) => void;
}) {
  const missing = summary.byEmotion.filter((item) => item.count === 0);
  return (
    <section aria-label="모은 카드" className="flex flex-col gap-3">
      <p className="text-body text-fg font-bold tabular-nums">
        모두 {summary.total}장
      </p>
      <div
        role="group"
        aria-label="감정으로 골라 보기"
        className="flex flex-wrap gap-2"
      >
        {summary.byEmotion.map(({ emotion, count }) =>
          count > 0 ? (
            <Chip
              key={emotion}
              data-emotion={emotion}
              pressed={selected === emotion}
              className="gap-1.5 tabular-nums"
              onClick={() => onSelect(selected === emotion ? null : emotion)}
            >
              <span aria-hidden className="bg-emotion size-2 rounded-full" />
              {emotionLabel(emotion)} {count}
            </Chip>
          ) : (
            <span
              key={emotion}
              className="border-line-strong text-body-sm text-fg-subtle inline-flex h-9 shrink-0 items-center rounded-full border border-dashed px-3.5 tabular-nums"
            >
              {emotionLabel(emotion)} 0
            </span>
          ),
        )}
      </div>
      {missing.length > 0 ? (
        <p className="text-label text-fg-subtle break-keep">
          아직 만나지 못한 몬스터:{" "}
          {missing.map((item) => emotionLabel(item.emotion)).join(", ")}
        </p>
      ) : null}
    </section>
  );
}
