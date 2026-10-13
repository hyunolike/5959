"use client";

import { useEffect, useRef, useState } from "react";

import { ApiError } from "@/shared/api";
import { cn } from "@/shared/lib";
import { GameButton } from "@/shared/ui";

import { useRaidAttackMutation } from "../api/use-raid-attack-mutation";

/** 서버가 쿨다운을 알려 주지 않았을 때 버튼을 잠가 두는 시간. 서버의 쿨다운과 같다. */
const DEFAULT_COOLDOWN_MS = 1_000;

/**
 * 보스 공격 버튼(006 US1). 누르면 공격을 보내고, 받아들여지면 응답의 `cooldownMs` 동안 잠긴다(US1-AC3).
 *
 * - 잠금은 편의일 뿐이고 판단은 서버가 한다. 다른 탭에서 방금 공격했으면 서버가 429를 주고, 그때는
 *   `Retry-After`만큼 잠근다. 거절된 공격은 HP와 기여를 바꾸지 않는다.
 * - 보스가 끝났거나 레이드가 쉬는 중이면 훅이 화면의 상태를 바꾼다. 여기서는 그 밖의 실패만 알린다.
 */
export function AttackButton({
  bossId,
  disabled = false,
}: {
  bossId: number;
  /** 레이드가 쉬는 중이면 부르는 쪽이 막는다. */
  disabled?: boolean;
}) {
  const attack = useRaidAttackMutation();
  const [cooling, setCooling] = useState(false);
  const [failed, setFailed] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (timer.current !== null) {
        clearTimeout(timer.current);
      }
    },
    [],
  );

  const coolFor = (ms: number) => {
    setCooling(true);
    if (timer.current !== null) {
      clearTimeout(timer.current);
    }
    timer.current = setTimeout(() => setCooling(false), ms);
  };

  const recharging = cooling || attack.isPending;
  const locked = disabled || recharging;

  return (
    <div className="flex w-full flex-col items-center gap-2">
      <GameButton
        // 잠긴 동안에도 초점이 남도록 disabled 대신 aria-disabled로 막는다. 연달아 누르는 버튼이다.
        aria-disabled={locked}
        onClick={() => {
          if (locked) {
            return;
          }
          setFailed(false);
          attack.mutate(bossId, {
            onSuccess: (result) => coolFor(result.cooldownMs),
            onError: (error) => {
              if (error instanceof ApiError && error.code === "RAID_COOLDOWN") {
                coolFor(
                  error.retryAfterSeconds !== undefined
                    ? error.retryAfterSeconds * 1_000
                    : DEFAULT_COOLDOWN_MS,
                );
                return;
              }
              const handled =
                error instanceof ApiError &&
                (error.code === "RAID_BOSS_ENDED" ||
                  error.code === "RAID_UNAVAILABLE");
              setFailed(!handled);
            },
          });
        }}
        className={cn(
          // 쿨다운 동안에는 눌린 채로 내려가 있다가 다시 올라온다. 움직임 줄이기에서는 그림자만 바뀐다.
          recharging && "translate-y-1 shadow-none motion-reduce:translate-y-0",
          // 레이드가 쉬는 중이면 회색으로 잠긴다.
          disabled &&
            "border-line-strong bg-surface-sunken text-fg-subtle cursor-not-allowed shadow-none active:translate-y-0",
        )}
      >
        공격하기
      </GameButton>
      {failed ? (
        <p role="alert" className="text-label text-danger">
          공격하지 못했어요. 잠시 후 다시 시도해 주세요.
        </p>
      ) : null}
    </div>
  );
}
