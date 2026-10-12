"use client";

import { PencilLine } from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { useGuestAccess } from "@/features/auth/guest-access";
import { Button } from "@/shared/ui";

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

const SPACES = [
  {
    label: "마음 이야기",
    body: "회원들이 올린 고민을 읽고 공감과 댓글을 남겨요.",
  },
  {
    label: "함께 이겨내기",
    body: "회원들과 힘을 모아 보스 몬스터에 맞서요.",
  },
  {
    label: "고민 쓰기",
    body: "고민을 적고, 듣고 싶은 댓글 말투를 골라요.",
  },
  {
    label: "내 마음 기록",
    body: "내 글과 활동, 감정 통계를 모아 봐요.",
  },
];

/** 공개 피드 API는 없다. 회원 데이터 요청/가짜 게시글 없이 실제 탐색 구조만 안내한다. */
export function GuestHome() {
  const { requestLogin } = useGuestAccess();
  return (
    <div className="flex w-full max-w-[720px] flex-col gap-8">
      <header>
        <h1 className="text-2xl font-bold text-neutral-900">마음 이야기</h1>
        <p className="mt-1 text-sm text-neutral-600">
          고민을 나누고 감정 몬스터를 함께 이겨내는 곳이에요.
        </p>
      </header>

      <section className="rounded-surface border border-neutral-200 bg-white px-5 py-8 sm:px-8 sm:py-10">
        <Image
          src="/brand/ogu-companion.png"
          alt=""
          width={96}
          height={79}
          className="mb-5 h-auto w-20 object-contain"
        />
        <h2 className="text-lg leading-relaxed font-bold text-neutral-900">
          마음 이야기는 로그인 후 볼 수 있어요
        </h2>
        <p className="mt-2 max-w-md text-sm leading-7 text-neutral-600">
          서로의 고민을 안전하게 나누기 위해 회원의 글은 로그인한 분께만
          보여드려요.
        </p>
        <div className="mt-6 flex flex-col gap-2 sm:flex-row">
          <Button type="button" onClick={() => requestLogin("/home")}>
            로그인하고 이야기 보기
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={() => requestLogin("/write")}
          >
            <PencilLine size={16} aria-hidden />
            고민 쓰기
          </Button>
        </div>
        <p className="mt-3 text-sm text-neutral-600">
          처음이라면{" "}
          <Link
            href="/signup"
            className="text-brand-700 inline-flex min-h-11 items-center font-bold underline-offset-4 hover:underline"
          >
            회원가입
          </Link>
        </p>
      </section>

      <section aria-labelledby="guest-home-steps">
        <h2
          id="guest-home-steps"
          className="text-base font-bold text-neutral-900"
        >
          오구오구는 이렇게 써요
        </h2>
        <ol className="mt-3 grid gap-x-6 gap-y-4 sm:grid-cols-3">
          {STEPS.map((step, index) => (
            <li key={step.title} className="flex gap-3 sm:flex-col sm:gap-2">
              <span
                aria-hidden
                className="flex size-7 shrink-0 items-center justify-center rounded-full bg-neutral-100 text-xs font-bold text-neutral-700 tabular-nums"
              >
                {index + 1}
              </span>
              <div>
                <p className="text-sm font-bold text-neutral-900">
                  {step.title}
                </p>
                <p className="mt-1 text-sm leading-6 text-neutral-600">
                  {step.body}
                </p>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section aria-labelledby="guest-home-spaces">
        <h2
          id="guest-home-spaces"
          className="text-base font-bold text-neutral-900"
        >
          메뉴 안내
        </h2>
        <p className="mt-1 text-xs leading-6 text-neutral-500">
          마음 이야기 외의 메뉴는 로그인 후 이용할 수 있어요.
        </p>
        <dl className="mt-3 divide-y divide-neutral-200 border-y border-neutral-200">
          {SPACES.map((space) => (
            <div
              key={space.label}
              className="flex flex-col gap-0.5 py-3 sm:flex-row sm:gap-6"
            >
              <dt className="shrink-0 text-sm font-bold text-neutral-900 sm:w-28">
                {space.label}
              </dt>
              <dd className="text-sm leading-6 text-neutral-600">
                {space.body}
              </dd>
            </div>
          ))}
        </dl>
      </section>
    </div>
  );
}
