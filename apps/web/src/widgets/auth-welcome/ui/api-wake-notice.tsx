"use client";

import Image from "next/image";
import { useEffect, useState } from "react";

import { useApiReadiness } from "@/shared/api";
import { Spinner } from "@/shared/ui";

function formatElapsed(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return minutes > 0 ? `${minutes}분 ${rest}초째` : `${rest}초째`;
}

/**
 * 서버가 잠들어 있을 때만 보이는 안내(018 US2-AC7). 깨어 있거나 아직 묻는 중이면 아무것도 그리지 않는다.
 * 기다리는 동안 입력은 그대로 할 수 있고, 제출은 폼이 깨어난 뒤에 보낸다(US2-AC8).
 */
export function ApiWakeNotice() {
  const { status, wakingSince } = useApiReadiness();
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (status !== "waking") return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [status]);

  const elapsed =
    wakingSince === null
      ? 0
      : Math.max(0, Math.floor((now - wakingSince) / 1000));

  if (status !== "waking" && status !== "unavailable") return null;

  const waking = status === "waking";
  return (
    <div
      role="status"
      aria-live="polite"
      className="bg-surface-sunken rounded-surface mx-auto flex w-full max-w-sm items-center gap-4 p-4"
    >
      <Image
        src="/brand/ogu-wake.webp"
        alt=""
        width={72}
        height={72}
        className="size-18 shrink-0"
      />
      <div className="flex min-w-0 flex-col gap-1">
        <p className="text-body text-fg flex items-center gap-2 font-bold break-keep">
          {waking ? "서버를 깨우고 있어요" : "서버가 아직 일어나지 않았어요"}
          {waking ? <Spinner className="size-3.5" label="깨우는 중" /> : null}
        </p>
        <p className="text-body-sm text-fg-subtle break-keep">
          {waking
            ? "오래 쉬던 서버라 일어나는 데 4~5분쯤 걸려요. 그동안 입력해 두면 준비되는 대로 보낼게요."
            : "잠시 뒤 다시 눌러 주세요. 누르면 다시 깨워 볼게요."}
        </p>
        {waking ? (
          <p className="text-caption text-fg-subtle tabular-nums">
            {formatElapsed(elapsed)}
          </p>
        ) : null}
      </div>
    </div>
  );
}
