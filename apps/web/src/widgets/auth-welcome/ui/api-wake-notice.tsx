"use client";

import Image from "next/image";
import { FootprintsIcon } from "@phosphor-icons/react";
import { useCallback, useEffect, useRef, useState } from "react";

import { useApiReadiness } from "@/shared/api";
import { Button, Spinner } from "@/shared/ui";

import { WakeMiniGame } from "./wake-mini-game";

function formatElapsed(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return minutes > 0 ? `${minutes}분 ${rest}초째` : `${rest}초째`;
}

/**
 * 서버가 잠들어 있을 때만 보이는 안내(018 US2-AC7). 깨어 있거나 아직 묻는 중이면 아무것도 그리지 않는다.
 * 기다리는 동안 입력은 그대로 할 수 있고, 제출은 폼이 깨어난 뒤에 보낸다(US2-AC8).
 */
export function ApiWakeNotice({
  context = "form",
  onPlayingChange,
}: {
  context?: "form" | "session";
  onPlayingChange?: (playing: boolean) => void;
}) {
  const { status, wakingSince } = useApiReadiness();
  const [now, setNow] = useState(() => Date.now());
  const [playing, setPlaying] = useState(false);
  // 인증 화면은 재연결 중에도 마운트되어 있다. 새 확인은 이전 놀이를 이어 하지 않는다.
  if (status === "checking" && playing) setPlaying(false);
  const startRef = useRef<HTMLButtonElement>(null);
  const closeGame = useCallback(() => {
    setPlaying(false);
    onPlayingChange?.(false);
    if (status === "waking")
      requestAnimationFrame(() => startRef.current?.focus());
  }, [onPlayingChange, status]);

  // 재연결 확인으로 안내가 교체될 때에도 부모에 놀이가 끝났음을 알린다.
  useEffect(() => () => onPlayingChange?.(false), [onPlayingChange]);

  useEffect(() => {
    if (status !== "waking") return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [status]);

  const elapsed =
    wakingSince === null
      ? 0
      : Math.max(0, Math.floor((now - wakingSince) / 1000));

  if (status !== "waking" && status !== "unavailable" && !playing) return null;

  const waking = status === "waking";
  return (
    <div className="bg-surface-sunken rounded-surface mx-auto w-full max-w-sm">
      {status !== "ready" ? (
        <div className="flex items-center gap-4 p-4">
          <Image
            src="/brand/ogu-wake.webp"
            alt=""
            width={72}
            height={72}
            className="size-18 shrink-0"
          />
          <div className="flex min-w-0 flex-col gap-1">
            <div role="status" aria-live="polite">
              <p className="text-body text-fg flex items-center gap-2 font-bold break-keep">
                {waking
                  ? "서버를 깨우고 있어요"
                  : "서버가 아직 일어나지 않았어요"}
                {waking ? (
                  <Spinner className="size-3.5" label="깨우는 중" />
                ) : null}
              </p>
              <p className="text-body-sm text-fg-subtle break-keep">
                {waking
                  ? context === "session"
                    ? "오래 쉬던 서버라 일어나는 데 4~5분쯤 걸려요. 준비되면 보고 있던 화면을 자동으로 불러올게요."
                    : "오래 쉬던 서버라 일어나는 데 4~5분쯤 걸려요. 그동안 입력해 두면 준비되는 대로 보낼게요."
                  : "잠시 뒤 다시 눌러 주세요. 누르면 다시 깨워 볼게요."}
              </p>
            </div>
            {waking ? (
              <p
                aria-live="off"
                className="text-caption text-fg-subtle tabular-nums"
              >
                {formatElapsed(elapsed)}
              </p>
            ) : null}
          </div>
        </div>
      ) : null}
      {playing ? (
        <WakeMiniGame status={status} onClose={closeGame} />
      ) : waking ? (
        <div className="border-line border-t px-4 py-3">
          <Button
            ref={startRef}
            type="button"
            variant="outline"
            className="w-full"
            onClick={() => {
              setPlaying(true);
              onPlayingChange?.(true);
            }}
          >
            <FootprintsIcon size={18} aria-hidden />
            오구와 놀기
          </Button>
          <p className="text-caption text-fg-subtle mt-2 text-center">
            기다리는 동안 오구와 산책해요. 톡 누르면 점프해요.
          </p>
        </div>
      ) : null}
    </div>
  );
}
