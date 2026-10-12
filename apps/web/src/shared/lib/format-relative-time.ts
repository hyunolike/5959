import dayjs from "dayjs";

/**
 * 작성 시각을 "12분 전"처럼 쓴다. 일주일이 지나면 날짜(`2026.10.06`)로 쓴다.
 * 기기 시계가 어긋나 시각이 미래로 보이면 "방금"으로, 읽을 수 없는 값은 빈 문자열로 돌려준다.
 */
export function formatRelativeTime(
  date: string | number | Date,
  now: string | number | Date = new Date(),
): string {
  const then = dayjs(date);
  if (!then.isValid()) return "";
  const minutes = dayjs(now).diff(then, "minute");
  if (minutes < 1) return "방금";
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}일 전`;
  return then.format("YYYY.MM.DD");
}
