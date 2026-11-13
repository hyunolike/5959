"use client";

import {
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useRef,
  type Ref,
} from "react";
import {
  createRunner,
  jumpRunner,
  stepRunner,
  WORLD_HEIGHT,
  WORLD_WIDTH,
} from "../model/wake-game";
import { nightAmount } from "../model/runner-sky";
import {
  drawRunner,
  type RunnerPalette,
  type RunnerPhase,
} from "./runner-renderer";

export type RunnerSnapshot = {
  phase: RunnerPhase;
  meters: number;
  night: boolean;
};
export type RunnerHandle = {
  act: () => void;
  pause: () => void;
  resume: () => void;
};

export function RunnerCanvas({
  ref,
  enabled,
  reducedMotion,
  onSnapshot,
  onLoad,
  onError,
}: {
  ref: Ref<RunnerHandle>;
  enabled: boolean;
  reducedMotion: boolean;
  onSnapshot: (snapshot: RunnerSnapshot) => void;
  onLoad: () => void;
  onError: () => void;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const controls = useRef<RunnerHandle | null>(null);
  const stop = useRef<(() => void) | null>(null);
  const repaint = useRef<(() => void) | null>(null);
  const settings = useRef({ enabled, reducedMotion });
  useLayoutEffect(() => {
    settings.current = { enabled, reducedMotion };
    if (!enabled) stop.current?.();
    else repaint.current?.();
  }, [enabled, reducedMotion]);
  useImperativeHandle(
    ref,
    () => ({
      act: () => controls.current?.act(),
      pause: () => controls.current?.pause(),
      resume: () => controls.current?.resume(),
    }),
    [],
  );

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    let disposed = false;
    let frame = 0;
    let phase: RunnerPhase = "idle";
    let state = createRunner();
    let lastFrame: number | null = null;
    let lastPublish = 0;
    let restartAt = 0;
    let context: CanvasRenderingContext2D | null = null;
    let landingFrom = 0;
    let landingAt = 0;
    const atlas = new Image();
    const style = getComputedStyle(canvas);
    const palette: RunnerPalette = {
      background: style.backgroundColor,
      line: style.borderTopColor,
      nightBackground: style.color,
      nightLine: style.outlineColor,
    };

    const publish = () =>
      onSnapshot({
        phase,
        meters: Math.floor(state.distance / 10),
        night: nightAmount(state.elapsed, settings.current.reducedMotion) > 0.6,
      });
    const paint = () => {
      if (context)
        drawRunner(
          context,
          atlas,
          state,
          phase,
          settings.current.reducedMotion,
          palette,
        );
    };
    const pause = () => {
      if (phase !== "running") return;
      phase = "paused";
      cancelAnimationFrame(frame);
      lastFrame = null;
      publish();
      paint();
    };
    repaint.current = () => {
      publish();
      paint();
    };
    const animate = (timestamp: number) => {
      if (disposed) return;
      if (!settings.current.enabled) {
        state = {
          ...state,
          y: landingFrom * Math.max(0, 1 - (timestamp - landingAt) / 180),
          obstacles: [],
          jumpAge: null,
        };
        paint();
        if (state.y > 0) frame = requestAnimationFrame(animate);
        return;
      }
      if (phase !== "running") return;
      const delta = lastFrame === null ? 0 : (timestamp - lastFrame) / 1000;
      lastFrame = timestamp;
      if (delta > 0.25 || document.visibilityState === "hidden") {
        pause();
        return;
      }
      state = stepRunner(state, delta);
      if (state.crashed) {
        phase = "crashed";
        restartAt = performance.now() + 350;
        publish();
      } else if (timestamp - lastPublish >= 100) {
        publish();
        lastPublish = timestamp;
      }
      paint();
      if (phase === "running") frame = requestAnimationFrame(animate);
    };
    const resume = () => {
      if (
        !settings.current.enabled ||
        !context ||
        document.visibilityState === "hidden"
      )
        return;
      if (phase !== "paused") return;
      phase = "running";
      lastFrame = null;
      publish();
      frame = requestAnimationFrame(animate);
    };
    controls.current = {
      pause,
      resume,
      act: () => {
        if (
          !settings.current.enabled ||
          !context ||
          document.visibilityState === "hidden"
        )
          return;
        if (phase === "crashed" && performance.now() < restartAt) return;
        if (phase === "idle" || phase === "crashed") {
          state = jumpRunner(createRunner(Date.now()));
          phase = "running";
          lastFrame = null;
          publish();
          paint();
          frame = requestAnimationFrame(animate);
        } else {
          if (phase === "paused") resume();
          state = jumpRunner(state);
          paint();
        }
      },
    };
    stop.current = () => {
      pause();
      landingFrom = state.y;
      landingAt = performance.now();
      state = { ...state, obstacles: [], jumpAge: null };
      if (landingFrom > 0 && !settings.current.reducedMotion)
        frame = requestAnimationFrame(animate);
      else {
        state = { ...state, y: 0 };
        paint();
      }
    };
    const resize = () => {
      const width = canvas.getBoundingClientRect().width || WORLD_WIDTH;
      const ratio = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.round(width * ratio);
      canvas.height = Math.round(
        ((width * WORLD_HEIGHT) / WORLD_WIDTH) * ratio,
      );
      context?.setTransform(
        canvas.width / WORLD_WIDTH,
        0,
        0,
        canvas.height / WORLD_HEIGHT,
        0,
        0,
      );
      paint();
    };
    atlas.onload = () => {
      if (disposed) return;
      try {
        context = canvas.getContext("2d");
      } catch {
        context = null;
      }
      if (!context) {
        onError();
        return;
      }
      resize();
      onLoad();
    };
    atlas.onerror = () => {
      if (!disposed) onError();
    };
    atlas.src = "/brand/runner/ogu-walk-atlas.png";
    const observer =
      typeof ResizeObserver === "undefined" ? null : new ResizeObserver(resize);
    observer?.observe(canvas);
    const visibility = () => {
      if (document.visibilityState === "hidden") pause();
    };
    document.addEventListener("visibilitychange", visibility);
    window.addEventListener("blur", pause);
    return () => {
      disposed = true;
      cancelAnimationFrame(frame);
      observer?.disconnect();
      document.removeEventListener("visibilitychange", visibility);
      window.removeEventListener("blur", pause);
      controls.current = null;
      stop.current = null;
      repaint.current = null;
      atlas.onload = null;
      atlas.onerror = null;
    };
  }, [onSnapshot, onLoad, onError]);

  return (
    <canvas
      ref={canvasRef}
      width={WORLD_WIDTH}
      height={WORLD_HEIGHT}
      aria-hidden
      className="border-line-strong bg-surface text-action outline-on-action block h-full w-full"
    />
  );
}
