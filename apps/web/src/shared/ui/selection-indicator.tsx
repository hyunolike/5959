"use client";

import { m } from "framer-motion";
import { useReducedMotionPreference } from "@/shared/lib";

export function SelectionIndicator({
  index,
  count,
}: {
  index: number;
  count: number;
}) {
  const reduce = useReducedMotionPreference();
  return (
    <m.span
      aria-hidden
      initial={false}
      className="bg-action pointer-events-none absolute bottom-0 left-0 h-0.5"
      style={{ width: `${100 / count}%` }}
      animate={{ x: `${index * 100}%` }}
      transition={{ duration: reduce ? 0 : 0.16, ease: "easeOut" }}
    />
  );
}
