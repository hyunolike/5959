"use client";

import { X } from "@phosphor-icons/react";
import Image from "next/image";
import Link from "next/link";
import { useEffect, useState, useSyncExternalStore } from "react";

import { useHasMyPostQuery } from "@/entities/post";
import { Button, IconButton } from "@/shared/ui";

import {
  dismissPrompt,
  forgetWelcome,
  isPromptDismissed,
  takeWelcome,
} from "../model/storage";

const subscribeNothing = () => () => {};

/** 서버 렌더와 첫 하이드레이션에서는 `false`, 그 뒤 브라우저에서는 `true`다. */
function useIsClient() {
  return useSyncExternalStore(
    subscribeNothing,
    () => true,
    () => false,
  );
}

/**
 * 회원 홈 맨 위의 첫 고민 안내(016 US2). 모양은 둘이다.
 * - 환영 안내: 온보딩을 마친 직후 한 번. 쓰기로 가거나 닫고 둘러본다.
 * - 첫 고민 카드: 지우지 않은 내 글이 없고 닫은 적이 없을 때. 글을 쓰면 사라진다.
 * 내 글이 있는지 모르면(받는 중, 실패) 아무것도 그리지 않는다(US2-AC9).
 */
export function FirstPostPrompt() {
  // 브라우저 저장소를 읽으므로 서버 렌더와 어긋나지 않게 브라우저에서만 그린다.
  return useIsClient() ? <ClientPrompt /> : null;
}

function ClientPrompt() {
  const hasMyPost = useHasMyPostQuery();
  const [welcome, setWelcome] = useState(takeWelcome);
  const [dismissed, setDismissed] = useState(isPromptDismissed);

  // 화면을 떠나면 이번 화면이 기억한 환영 표시를 비운다. 다시 오면 저장소에는 값이 없다(US2-AC5).
  useEffect(() => forgetWelcome, []);

  if (welcome) {
    return (
      <aside
        aria-labelledby="welcome-title"
        className="rounded-surface bg-surface-sunken flex flex-col gap-5 p-5 sm:flex-row sm:items-center sm:gap-6"
      >
        <Image
          src="/brand/ogu-hello.webp"
          alt=""
          width={96}
          height={96}
          className="size-20 shrink-0 self-center sm:size-24"
        />
        <div className="flex min-w-0 flex-1 flex-col gap-4">
          <div className="flex flex-col gap-1">
            <h2 id="welcome-title" className="text-title text-fg font-bold">
              준비가 끝났어요
            </h2>
            <p className="text-body-sm text-fg-subtle break-keep">
              요즘 마음에 걸리는 일이 있나요? 털어놓으면 고민이 몬스터가 되고,
              회원들이 함께 물리쳐 줘요.
            </p>
          </div>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button asChild data-umami-event="welcome-write">
              <Link href="/write">털어놓기</Link>
            </Button>
            <Button
              type="button"
              variant="ghost"
              data-umami-event="welcome-browse"
              onClick={() => {
                // 이번 화면에서는 첫 고민 카드도 다시 띄우지 않는다. 다음에 홈을 열면 보인다.
                setWelcome(false);
                setDismissed(true);
              }}
            >
              먼저 둘러볼게요
            </Button>
          </div>
        </div>
      </aside>
    );
  }

  if (hasMyPost !== false || dismissed) return null;

  return (
    <aside
      aria-label="첫 고민 안내"
      className="border-line rounded-surface flex items-center gap-3 border py-2 pr-1 pl-4"
    >
      <p className="text-body-sm text-fg min-w-0 flex-1 break-keep">
        첫 고민을 남기면 몬스터가 나타나요.
      </p>
      <Button
        asChild
        size="sm"
        className="shrink-0"
        data-umami-event="first-post-card-write"
      >
        <Link href="/write">첫 고민 남기기</Link>
      </Button>
      <IconButton
        aria-label="첫 고민 안내 닫기"
        data-umami-event="first-post-card-dismiss"
        onClick={() => {
          dismissPrompt();
          setDismissed(true);
        }}
      >
        <X size={18} aria-hidden />
      </IconButton>
    </aside>
  );
}
