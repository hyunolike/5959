"use client";

import { X } from "@phosphor-icons/react";
import Link from "next/link";

import { useOptionalInstallApp } from "@/features/install-app";
import { AI_FRIENDS_PATH, Button, IconButton } from "@/shared/ui";

/**
 * 첫 글을 올린 직후 글 화면에 한 번 보이는 안내(016 US3). 앞으로 일어날 일을 알려 다시 올 까닭을
 * 남긴다. HP 규칙은 003(공감 1, 첫 댓글 3), AI 친구는 009와 012, 알림은 004를 따른다.
 * 설치를 권할 수 있는 브라우저면 알림을 놓치지 않게 설치 길을 둔다. 권한을 스스로 묻지 않는다.
 */
export function FirstPostGuide({ onClose }: { onClose: () => void }) {
  const install = useOptionalInstallApp();
  const canSuggestInstall = !!install?.environment && !install.installed;

  return (
    <section
      aria-labelledby="first-post-guide-title"
      className="border-line rounded-surface flex flex-col gap-3 border p-4"
    >
      <div className="flex items-start justify-between gap-2">
        <h2
          id="first-post-guide-title"
          className="text-body text-fg pt-2.5 font-bold"
        >
          첫 고민을 남겼어요
        </h2>
        <IconButton aria-label="첫 글 안내 닫기" onClick={onClose}>
          <X size={18} aria-hidden />
        </IconButton>
      </div>
      <ul className="text-body-sm text-fg-muted flex list-disc flex-col gap-1.5 pl-5 leading-6 break-keep">
        <li>
          공감 한 번에 HP 1, 첫 댓글에 HP 3이 줄어요. HP가 다 떨어지면 몬스터가
          쓰러져요.
        </li>
        <li>
          한동안 사람의 댓글이 없으면{" "}
          <Link
            href={AI_FRIENDS_PATH}
            className="text-fg font-bold underline underline-offset-4"
          >
            AI 친구
          </Link>
          가 먼저 답해요.
        </li>
        <li>공감과 댓글이 오면 알림으로 알려 드려요.</li>
      </ul>
      {canSuggestInstall ? (
        <div className="bg-surface-sunken rounded-control flex flex-wrap items-center gap-2 px-3 py-2.5">
          <p className="text-body-sm text-fg min-w-0 flex-1 break-keep">
            앱으로 설치하면 알림을 놓치지 않아요.
          </p>
          {install.canPrompt ? (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => void install.requestInstall()}
            >
              홈 화면에 추가
            </Button>
          ) : (
            <p className="text-caption text-fg-subtle">
              브라우저 메뉴의 &lsquo;홈 화면에 추가&rsquo;로 설치할 수 있어요.
            </p>
          )}
        </div>
      ) : null}
    </section>
  );
}
