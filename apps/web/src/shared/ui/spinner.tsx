import { cn } from "@/shared/lib";

/**
 * 불러오는 중 표시. 도는 그림은 낭독하지 않고, 화면 낭독기에는 [label]을 글자로 전한다.
 * `className`은 도는 그림의 크기와 색에 쓴다.
 */
export function Spinner({
  className,
  label = "불러오는 중",
}: {
  className?: string;
  label?: string;
}) {
  return (
    <span role="status" className="inline-flex">
      <span className="sr-only">{label}</span>
      <span
        aria-hidden
        className={cn(
          "inline-block size-4 rounded-full border-2 border-current border-t-transparent motion-safe:animate-spin",
          className,
        )}
      />
    </span>
  );
}
