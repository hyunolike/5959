"use client";

import { PencilSimpleLine } from "@phosphor-icons/react";
import Link from "next/link";

import {
  EMOTION_LABELS,
  MonsterThumb,
  type EmotionType,
} from "@/entities/monster";
import { useGuestAccess } from "@/features/auth/guest-access";
import { Button } from "@/shared/ui";

const EMOTIONS = Object.keys(EMOTION_LABELS) as EmotionType[];

const STEPS = [
  {
    title: "고민을 적어요",
    body: "요즘 마음을 무겁게 하는 일을 적으면 그 감정이 몬스터로 나타나요.",
  },
  {
    title: "공감과 댓글을 받아요",
    body: "글을 읽은 회원들이 공감을 누르고 댓글을 남겨요.",
  },
  {
    title: "함께 이겨내요",
    body: "공감과 댓글이 모일수록 몬스터가 약해져요.",
  },
];

/**
 * 비회원이 보는 홈. 공개 피드 API는 없다. 회원의 글과 수치, 가짜 게시글 없이
 * 실제 몬스터 그림으로 무엇을 하는 곳인지 보여 주고, 참여하려 하면 로그인 안내를 연다.
 * 메뉴는 화면의 메뉴가 잠금 표시와 함께 보여 주므로 여기서 되풀이하지 않는다.
 */
export function GuestHome() {
  const { requestLogin } = useGuestAccess();
  return (
    <div className="flex w-full max-w-[720px] flex-col gap-8">
      <header>
        <h1 className="text-heading text-fg font-bold">마음 이야기</h1>
        <p className="text-body-sm text-fg-subtle mt-1">
          고민을 나누고 감정 몬스터를 함께 이겨내는 곳이에요.
        </p>
      </header>

      <section
        aria-labelledby="guest-home-monsters"
        className="rounded-surface bg-surface-sunken flex flex-col gap-4 p-5"
      >
        <h2 id="guest-home-monsters" className="text-body text-fg font-bold">
          고민은 감정 몬스터가 돼요
        </h2>
        <ul className="grid grid-cols-5 gap-2">
          {EMOTIONS.map((emotion) => (
            <li
              key={emotion}
              className="flex min-w-0 flex-col items-center gap-1.5"
            >
              <MonsterThumb emotion={emotion} stage="full" />
              <span className="text-caption text-fg-muted font-medium break-keep">
                {EMOTION_LABELS[emotion]}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section className="flex flex-col gap-2">
        <h2 className="text-title text-fg font-bold break-keep">
          마음 이야기는 로그인 후 볼 수 있어요
        </h2>
        <p className="text-body-sm text-fg-subtle max-w-md break-keep">
          서로의 고민을 안전하게 나누기 위해 회원의 글은 로그인한 분께만
          보여드려요.
        </p>
        <div className="mt-3 flex flex-col gap-2 sm:flex-row">
          <Button type="button" size="lg" onClick={() => requestLogin("/home")}>
            로그인하고 이야기 보기
          </Button>
          <Button
            type="button"
            size="lg"
            variant="secondary"
            onClick={() => requestLogin("/write")}
          >
            <PencilSimpleLine size={18} aria-hidden />
            고민 쓰기
          </Button>
        </div>
        <p className="text-body-sm text-fg-subtle">
          처음이라면{" "}
          <Link
            href="/signup"
            className="text-fg inline-flex min-h-11 items-center font-bold underline-offset-4 hover:underline"
          >
            회원가입
          </Link>
        </p>
      </section>

      <section aria-labelledby="guest-home-steps">
        <h2 id="guest-home-steps" className="text-body text-fg font-bold">
          오구오구는 이렇게 써요
        </h2>
        <ol className="mt-3 grid gap-x-6 gap-y-4 sm:grid-cols-3">
          {STEPS.map((step, index) => (
            <li key={step.title} className="flex gap-3 sm:flex-col sm:gap-2">
              <span
                aria-hidden
                className="bg-surface-sunken text-caption text-fg-muted flex size-7 shrink-0 items-center justify-center rounded-full font-bold tabular-nums"
              >
                {index + 1}
              </span>
              <div>
                <p className="text-body-sm text-fg font-bold">{step.title}</p>
                <p className="text-body-sm text-fg-subtle mt-1 break-keep">
                  {step.body}
                </p>
              </div>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
