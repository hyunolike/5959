import {
  ChatCircleDots,
  Heart,
  PencilSimpleLine,
} from "@phosphor-icons/react/dist/ssr";
import Image from "next/image";

import { cn } from "@/shared/lib";

import type { HpStage } from "../model/hp-stage";
import { spritePath } from "../model/sprite";

const EMOTION = "ANXIETY";
const STAGES: HpStage[] = ["full", "hurt", "weak", "defeated"];

function Panel({
  step,
  caption,
  children,
}: {
  step: 1 | 2 | 3;
  caption: string;
  children: React.ReactNode;
}) {
  return (
    <div
      data-loop-panel
      className={cn(
        "ogu-loop-panel flex min-w-0 flex-col items-center gap-2",
        `ogu-loop-panel-${step}`,
      )}
    >
      <div className="rounded-surface bg-surface relative flex aspect-square w-full items-center justify-center overflow-hidden p-2">
        {children}
      </div>
      <span className="text-caption text-fg-muted font-bold break-keep">
        {caption}
      </span>
    </div>
  );
}

/**
 * 사용법 장면(016 US5). 쓰기 → 몬스터 등장 → 공감과 댓글에 HP가 줄어 쓰러짐을 세 칸으로 보인다.
 * 실제 몬스터 그림과 상세의 HP 막대 모양을 쓰고, 소리 없이 8초에 한 바퀴 돈다(research R8).
 * 움직임은 `globals.css`의 `ogu-loop-*`에 있고 움직임 줄이기에서는 멈춘 세 칸이 된다.
 * 장면 전체가 그림 하나로 읽히고 안의 그림과 글자는 숨긴다. 그림은 늦게 받는다(FR-023).
 */
export function MonsterLifecycleLoop({ className }: { className?: string }) {
  return (
    <div
      role="img"
      aria-label="고민을 쓰면 몬스터가 나타나고, 공감과 댓글이 모이면 HP가 줄어 쓰러져요"
      data-emotion={EMOTION}
      className={cn("rounded-surface bg-emotion-tint p-3 sm:p-4", className)}
    >
      <div aria-hidden className="grid grid-cols-3 gap-2 sm:gap-3">
        <Panel step={1} caption="쓰기">
          <div className="flex w-full flex-col gap-1.5">
            <PencilSimpleLine
              size={20}
              className="text-emotion-fg self-center"
            />
            <span className="bg-line h-1.5 w-full rounded-full" />
            <span className="bg-line h-1.5 w-4/5 rounded-full" />
            <span className="ogu-loop-typing bg-emotion h-1.5 w-3/5 rounded-full" />
          </div>
        </Panel>
        <Panel step={2} caption="등장">
          <Image
            src={spritePath(EMOTION, "full")}
            alt=""
            width={512}
            height={512}
            sizes="96px"
            loading="lazy"
            draggable={false}
            className="ogu-loop-appear size-full object-contain select-none"
          />
        </Panel>
        <Panel step={3} caption="함께 물리치기">
          <div className="relative size-full">
            {STAGES.map((stage) => (
              <Image
                key={stage}
                src={spritePath(EMOTION, stage)}
                alt=""
                width={512}
                height={512}
                sizes="96px"
                loading="lazy"
                draggable={false}
                className={cn(
                  "ogu-loop-sprite absolute inset-0 size-full object-contain pb-4 select-none",
                  `ogu-loop-sprite-${stage}`,
                )}
              />
            ))}
            <Heart
              size={18}
              weight="fill"
              className="ogu-loop-icon ogu-loop-like text-emotion-fg absolute top-0 left-0"
            />
            <ChatCircleDots
              size={18}
              weight="fill"
              className="ogu-loop-icon ogu-loop-comment text-emotion-fg absolute top-0 right-0"
            />
            <div className="border-game-line bg-surface absolute inset-x-0 bottom-0 h-3 overflow-hidden rounded-full border-2">
              <div className="ogu-loop-hp-fill bg-emotion h-full w-full" />
            </div>
          </div>
        </Panel>
      </div>
    </div>
  );
}
