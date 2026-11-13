"use client";

import {
  ArrowCounterClockwiseIcon,
  PauseIcon,
  PlayIcon,
  XIcon,
} from "@phosphor-icons/react";
import { useCallback, useEffect, useId, useRef, useState } from "react";

import type { ApiReadinessStatus } from "@/shared/api";
import { cn, useReducedMotionPreference } from "@/shared/lib";
import { Button, IconButton } from "@/shared/ui";
import {
  RunnerCanvas,
  type RunnerHandle,
  type RunnerSnapshot,
} from "./runner-canvas";

const BEST_KEY = "ogu-walk-best-v1";
let memoryBest = 0;
function savedBest() {
  try {
    const value = Number(sessionStorage.getItem(BEST_KEY));
    return Math.max(
      memoryBest,
      Number.isFinite(value) && value > 0 ? Math.floor(value) : 0,
    );
  } catch {
    return memoryBest;
  }
}

export function WakeMiniGame({
  status,
  onClose,
}: {
  status: ApiReadinessStatus;
  onClose: () => void;
}) {
  const titleId = useId();
  const helpId = useId();
  const reduce = useReducedMotionPreference();
  const controller = useRef<RunnerHandle>(null);
  const stage = useRef<HTMLButtonElement>(null);
  const [snapshot, setSnapshot] = useState<RunnerSnapshot>({
    phase: "idle",
    meters: 0,
    night: false,
  });
  const [asset, setAsset] = useState<"loading" | "loaded" | "error">("loading");
  const [best, setBest] = useState(savedBest);
  const ready = status === "ready";
  const unavailable = status === "unavailable";
  const enabled = status === "waking";
  const handleSnapshot = useCallback((next: RunnerSnapshot) => {
    setSnapshot(next);
    if (next.phase !== "running")
      setBest((previous) => {
        const highest = Math.max(previous, next.meters);
        memoryBest = Math.max(memoryBest, highest);
        if (highest > previous)
          try {
            sessionStorage.setItem(BEST_KEY, String(highest));
          } catch {
            /* 저장 없이도 산책은 계속한다. */
          }
        return highest;
      });
  }, []);
  const handleLoad = useCallback(() => setAsset("loaded"), []);
  const handleError = useCallback(() => setAsset("error"), []);
  const close = useCallback(() => {
    controller.current?.pause();
    onClose();
  }, [onClose]);
  useEffect(() => {
    if (!ready) return;
    const timer = setTimeout(close, 1_200);
    return () => clearTimeout(timer);
  }, [ready, close]);

  const label = ready
    ? "산책 완료"
    : !enabled
      ? "산책 멈춤"
      : asset === "loading"
        ? "산책 준비 중"
        : asset === "error"
          ? "산책 화면을 불러올 수 없음"
          : snapshot.phase === "idle"
            ? "톡 눌러 함께 산책하기"
            : snapshot.phase === "paused"
              ? "이어서 산책하기"
              : snapshot.phase === "crashed"
                ? "다시 산책하기"
                : "점프하기";
  const locked = !enabled || asset !== "loaded";
  const act = () => {
    if (!locked) controller.current?.act();
  };
  const message = ready
    ? "준비됐어요"
    : unavailable
      ? "연결을 다시 확인해 주세요"
      : asset === "loading"
        ? "산책을 준비하고 있어요"
        : asset === "error"
          ? "산책 화면을 불러오지 못했어요"
          : snapshot.phase === "idle"
            ? "톡 눌러 함께 산책하기"
            : snapshot.phase === "paused"
              ? "잠깐 멈췄어요"
              : snapshot.phase === "crashed"
                ? "잠깐 쉬어 가요"
                : "산책을 시작했어요";
  const overlay =
    !enabled || asset !== "loaded" || snapshot.phase !== "running";

  return (
    <section
      aria-labelledby={titleId}
      className="border-line border-t p-4"
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null))
          controller.current?.pause();
      }}
    >
      <div className="flex items-start justify-between gap-2">
        <div>
          <h2 id={titleId} className="text-body font-bold">
            오구의 마음 산책
          </h2>
          <p className="text-caption text-fg-subtle mt-1">
            작은 마음과 함께, 한 걸음씩.
          </p>
        </div>
        <IconButton aria-label="놀이 닫기" onClick={close}>
          <XIcon size={18} aria-hidden />
        </IconButton>
      </div>

      <div className="mt-4 flex items-baseline justify-between gap-2 tabular-nums">
        <span className="text-body-sm font-bold">함께 {snapshot.meters}m</span>
        <span className="text-caption text-fg-subtle">
          이번 방문 최고 {Math.max(best, snapshot.meters)}m
        </span>
      </div>

      <div className="rounded-control border-line relative mt-3 overflow-hidden border">
        <button
          ref={stage}
          type="button"
          autoFocus
          aria-label={label}
          aria-describedby={helpId}
          aria-disabled={locked}
          data-night={snapshot.night}
          className={cn(
            "rounded-control bg-surface text-fg block aspect-[16/11] w-full touch-manipulation focus-visible:-outline-offset-3",
            snapshot.night && "focus-visible:outline-on-action",
          )}
          onClick={(event) => {
            event.currentTarget.focus({ preventScroll: true });
            act();
          }}
          onKeyDown={(event) => {
            if ([" ", "ArrowUp", "Enter"].includes(event.key)) {
              event.preventDefault();
              if (!event.repeat) act();
            }
          }}
        >
          <RunnerCanvas
            ref={controller}
            enabled={enabled}
            reducedMotion={reduce}
            onSnapshot={handleSnapshot}
            onLoad={handleLoad}
            onError={handleError}
          />
        </button>
        <div
          role="status"
          aria-live="polite"
          className={
            overlay
              ? "bg-surface/95 text-fg rounded-control pointer-events-none absolute top-5 right-3 left-[40%] p-2 text-center"
              : "sr-only"
          }
        >
          <p className="text-body font-bold break-keep">{message}</p>
          {overlay && asset === "loaded" ? (
            <p className="text-caption text-fg-subtle mt-1 break-keep">
              {ready
                ? "이제 마음 이야기를 나눠요."
                : unavailable
                  ? "잠시 뒤 다시 연결해 볼 수 있어요."
                  : snapshot.phase === "crashed"
                    ? `${snapshot.meters}m를 함께 걸었어요. 톡 눌러 다시 출발해요.`
                    : snapshot.phase === "paused"
                      ? "준비되면 눌러서 이어 가요."
                      : "돌부리는 가볍게 폴짝 넘어요."}
            </p>
          ) : null}
        </div>
      </div>

      <div className="mt-3 flex items-center justify-between gap-2">
        <p id={helpId} className="text-caption text-fg-subtle break-keep">
          {reduce
            ? "직접 시작하면 길이 움직여요. 터치 또는 Space로 점프."
            : "터치 또는 Space · ↑로 점프"}
        </p>
        {enabled && snapshot.phase === "running" ? (
          <IconButton
            aria-label="산책 일시정지"
            onClick={() => {
              stage.current?.focus({ preventScroll: true });
              controller.current?.pause();
            }}
          >
            <PauseIcon size={18} aria-hidden />
          </IconButton>
        ) : null}
      </div>

      {ready ? (
        <Button type="button" className="mt-3 w-full" onClick={close}>
          바로 계속하기
        </Button>
      ) : asset === "error" ? (
        <Button
          type="button"
          variant="outline"
          className="mt-3 w-full"
          onClick={close}
        >
          안내로 돌아가기
        </Button>
      ) : enabled && snapshot.phase === "paused" ? (
        <Button
          type="button"
          variant="outline"
          className="mt-3 w-full"
          onClick={() => {
            stage.current?.focus({ preventScroll: true });
            controller.current?.resume();
          }}
        >
          <PlayIcon size={18} aria-hidden />
          이어서 걷기
        </Button>
      ) : enabled && snapshot.phase === "crashed" ? (
        <p className="text-caption text-fg-subtle mt-2 flex items-center justify-center gap-1">
          <ArrowCounterClockwiseIcon size={14} aria-hidden />톡 누르면 다시
          산책해요
        </p>
      ) : null}
    </section>
  );
}
