import dayjs from "dayjs";

export function formatDate(
  date: string | number | Date,
  template = "MMM D, YYYY",
) {
  return dayjs(date).format(template);
}
