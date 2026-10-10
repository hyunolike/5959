"use client";

import dynamic from "next/dynamic";
import {
  Component,
  createContext,
  useContext,
  useRef,
  useState,
  type ErrorInfo,
  type ReactNode,
} from "react";

import { cn } from "@/shared/lib";

import { appearance } from "../model/appearance";
import { monsterLabel } from "../model/sprite";
import { EMOTION_LABELS, type MonsterView } from "../model/types";
import { MonsterSprite } from "./monster-sprite";
import { useCanRender3D } from "./render-mode";
import { useHitReaction } from "./use-hit-reaction";

/** 3D를 불러오는 동안 그 자리에 보일 정지 이미지. `next/dynamic`의 loading은 props를 받지 않아 컨텍스트로 넘긴다. */
const LoadingSpriteContext = createContext<ReactNode>(null);

function LoadingSprite() {
  return useContext(LoadingSpriteContext);
}

/** three와 R3F는 이 동적 import로만 불러온다. 첫 로딩 번들에 들어가지 않는다(ADR-0003). */
const Monster3D = dynamic(() => import("./monster-3d"), {
  ssr: false,
  loading: () => <LoadingSprite />,
});

/**
 * 3D 장면이 실패하면(WebGLRenderer를 만들지 못했거나 청크를 불러오지 못했을 때) 글 상세 전체가
 * 오류 화면으로 넘어가지 않게 여기서 받아 정지 이미지를 보여 준다(US5-AC4).
 */
class Monster3DBoundary extends Component<
  { fallback: ReactNode; onError: () => void; children: ReactNode },
  { failed: boolean }
> {
  state = { failed: false };

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error(
      "3D 몬스터를 그리지 못해 정지 이미지로 대신한다",
      error,
      info,
    );
    this.props.onError();
  }

  render() {
    return this.state.failed ? this.props.fallback : this.props.children;
  }
}

const DETAIL_SIZES = "(min-width: 640px) 224px, 192px";
const BOSS_SIZES = "(min-width: 640px) 288px, 240px";

/**
 * 몬스터 자리(US5). 감정 이름, 몬스터 그림, HP 바와 숫자, 처치됨 표시를 보여 준다.
 *
 * - `card`(피드): 항상 정지 이미지다(US5-AC3). 피드에 WebGL 캔버스를 여러 개 띄우지 않는다.
 * - `detail`(글 상세): WebGL을 쓸 수 있고 움직임 줄이기가 꺼져 있으면 3D 장면,
 *   아니면 정지 이미지다(US5-AC4).
 *
 * HP가 줄면 맞는 반응을 한다(US3-AC10). 정지 이미지면 그림과 HP 바를 함께 흔들고,
 * 3D면 장면이 0.4초 흔들리며 깜빡이고 HP 바만 흔든다. 움직임 줄이기면 흔들지 않는다.
 * 3D 장면이 실패하면 같은 글(`resetKey`)에서는 그 뒤로 정지 이미지로 그리고, 다른 글이면 다시 시도한다.
 */
export function MonsterDisplay({
  monster,
  variant,
  resetKey,
  className,
}: {
  /** 글의 몬스터이거나 레이드 보스다. 보스는 최대 HP가 훨씬 크다(006). */
  monster: Pick<MonsterView, "emotion" | "status"> & {
    hp: number;
    maxHp: number;
  };
  /** `boss`는 `detail`과 같되 더 크게 그린다(ADR-0003, 006 research R14). */
  variant: "detail" | "card" | "boss";
  /**
   * 몬스터가 속한 글을 가리키는 값(글 ID). 3D가 실패하면 이 값이 같은 동안은 정지 이미지로 남고,
   * 바뀌면(App Router가 트리를 유지한 채 다른 글로 갔을 때) 3D를 다시 시도한다.
   */
  resetKey?: string | number;
  className?: string;
}) {
  const can3D = useCanRender3D();
  // 실패를 어느 글에서 겪었는지 기억한다. 다른 글이면 실패가 아니다.
  const [failure, setFailure] = useState<{ key: typeof resetKey } | null>(null);
  const failed3D = failure !== null && failure.key === resetKey;
  const use3D = variant !== "card" && can3D && !failed3D;
  const look = appearance(
    monster.emotion,
    monster.hp / monster.maxHp,
    monster.status,
  );
  const label = monsterLabel(monster.emotion, look.stage);
  const percent = Math.round((monster.hp / monster.maxHp) * 100);

  const rootRef = useRef<HTMLDivElement>(null);
  const hpRef = useRef<HTMLDivElement>(null);
  useHitReaction(monster.hp, use3D ? hpRef : rootRef);

  const sprite = (
    <MonsterSprite
      emotion={monster.emotion}
      stage={look.stage}
      sizes={
        variant === "card"
          ? "64px"
          : variant === "boss"
            ? BOSS_SIZES
            : DETAIL_SIZES
      }
    />
  );

  const detail = variant !== "card";

  return (
    <div
      ref={rootRef}
      className={cn(
        detail ? "flex flex-col items-center gap-3" : "flex items-center gap-3",
        className,
      )}
    >
      <div
        className={cn(
          "shrink-0",
          variant === "boss"
            ? "size-60 sm:size-72"
            : detail
              ? "size-48 sm:size-56"
              : "size-16",
        )}
      >
        {use3D ? (
          <Monster3DBoundary
            key={resetKey}
            fallback={sprite}
            onError={() => setFailure({ key: resetKey })}
          >
            <LoadingSpriteContext value={sprite}>
              <Monster3D
                look={look}
                hp={monster.hp}
                label={label}
                className="size-full"
              />
            </LoadingSpriteContext>
          </Monster3DBoundary>
        ) : (
          sprite
        )}
      </div>
      <div className="flex w-full min-w-0 flex-col gap-2">
        <div className="flex items-center gap-2">
          <p className="text-lg font-semibold text-neutral-900">
            {EMOTION_LABELS[monster.emotion]}
          </p>
          {monster.status === "DEFEATED" ? (
            <span className="rounded-full bg-neutral-900 px-2 py-0.5 text-xs text-white">
              처치됨
            </span>
          ) : null}
        </div>
        <div ref={hpRef} className="flex flex-col gap-2">
          <div
            role="progressbar"
            aria-label="몬스터 HP"
            aria-valuemin={0}
            aria-valuemax={monster.maxHp}
            aria-valuenow={monster.hp}
            className="h-3 w-full overflow-hidden rounded-full bg-neutral-200"
          >
            <div
              className="h-full rounded-full bg-red-500 transition-[width] motion-reduce:transition-none"
              style={{ width: `${percent}%` }}
            />
          </div>
          <p className="text-xs text-neutral-500 tabular-nums">
            HP {monster.hp}/{monster.maxHp}
          </p>
        </div>
      </div>
    </div>
  );
}
