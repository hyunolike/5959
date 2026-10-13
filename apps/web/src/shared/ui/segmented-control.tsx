import { cn } from "@/shared/lib";

interface SegmentedControlProps<T extends string> {
  /** 묶음의 이름. 화면에는 보이지 않고 보조기기가 읽는다. */
  label: string;
  options: readonly { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
  className?: string;
}

/**
 * 둘이나 셋 가운데 하나를 고르는 세그먼트(정렬). 옅은 바탕 안에서 고른 칸만 흰 면으로 떠 있다.
 * 칸의 보이는 높이는 32px이고 `::before`로 누르는 영역을 44px로 맞춘다.
 */
export function SegmentedControl<T extends string>({
  label,
  options,
  value,
  onChange,
  className,
}: SegmentedControlProps<T>) {
  return (
    <div
      role="group"
      aria-label={label}
      className={cn(
        "bg-surface-sunken rounded-control-sm inline-flex gap-0.5 p-1",
        className,
      )}
    >
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={option.value === value}
          onClick={() => {
            if (option.value !== value) {
              onChange(option.value);
            }
          }}
          className="ogu-pressable rounded-inline text-body-sm text-fg-subtle aria-pressed:bg-surface aria-pressed:text-fg aria-pressed:shadow-raised relative h-8 px-3.5 font-medium before:absolute before:inset-x-0 before:-inset-y-1.5 before:content-[''] aria-pressed:font-bold"
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
