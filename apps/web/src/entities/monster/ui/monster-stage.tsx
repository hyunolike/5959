import type { ComponentProps } from "react";

import { cn } from "@/shared/lib";

import type { EmotionType } from "../model/types";

/**
 * 글 상세와 레이드의 무대. 감정의 옅은 색으로 채우고, 안의 감정 색 유틸리티가 이 감정을 따른다.
 * 감정이 아직 없으면(분석 중) 속성을 붙이지 않아 회색 기본값이 된다.
 * `role`과 `aria-label` 같은 나머지 속성은 그대로 넘겨 무대가 곧 구역이 될 수 있다.
 */
export function MonsterStage({
  emotion,
  className,
  ...props
}: ComponentProps<"div"> & { emotion?: EmotionType }) {
  return (
    <div
      data-emotion={emotion}
      className={cn("bg-emotion-tint", className)}
      {...props}
    />
  );
}
