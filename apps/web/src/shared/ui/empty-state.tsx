import Image from "next/image";
import type { ReactNode } from "react";

import { cn } from "@/shared/lib";

/**
 * 빈 화면과 오류를 한 모양으로 맞춘다: 오구 그림, 제목, 설명, 버튼 자리(스펙 "공용 부품").
 * 오류(`tone="error"`)면 제목과 설명을 `role="alert"` 하나로 읽힌다.
 * 화면의 제목(`h1`)은 이 부품이 아니라 화면이 둔다.
 */
export function EmptyState({
  imageSrc,
  title,
  description,
  tone = "empty",
  className,
  children,
}: {
  /** `public/brand`의 오구 그림. 장식이라 이름을 붙이지 않는다. */
  imageSrc?: string;
  title: string;
  description?: string;
  tone?: "empty" | "error";
  className?: string;
  /** 버튼 자리. 하나를 권하고 많아도 둘이다. */
  children?: ReactNode;
}) {
  return (
    <div
      className={cn(
        "flex w-full flex-col items-center gap-5 px-6 py-10 text-center",
        className,
      )}
    >
      {imageSrc ? (
        <Image
          src={imageSrc}
          alt=""
          width={132}
          height={132}
          className="size-33"
        />
      ) : null}
      <p
        role={tone === "error" ? "alert" : undefined}
        className="flex flex-col gap-1.5"
      >
        <strong className="text-title text-fg font-extrabold break-keep">
          {title}
        </strong>
        {description ? (
          <>
            {" "}
            <span className="text-body-sm text-fg-subtle font-normal break-keep">
              {description}
            </span>
          </>
        ) : null}
      </p>
      {children ? (
        <span className="flex flex-wrap justify-center gap-2">{children}</span>
      ) : null}
    </div>
  );
}
