import type { Metadata } from "next";
import Link from "next/link";

import { Button } from "@/shared/ui";

export const metadata: Metadata = { title: "AI 친구" };

const FRIENDS = [
  { name: "다정한 오구", voice: "먼저 마음을 받아 주는 포근한 말씨예요." },
  { name: "씩씩한 오구", voice: "시원하게 편들어 주는 힘 있는 말씨예요." },
  { name: "차분한 오구", voice: "한 가지를 조용히 짚어 주는 담백한 말씨예요." },
];

const FACTS = [
  {
    title: "AI가 쓴 댓글이에요",
    body: "AI 친구는 사람이 아니라 생성형 AI예요. 닉네임 옆에 언제나 AI 친구라고 표시해요.",
  },
  {
    title: "댓글이 없는 글에 먼저 답해요",
    body: "글이 올라오고 한동안 다른 회원의 댓글이 없으면 AI 친구가 댓글과 공감을 남겨요. 글쓴이가 고른 댓글 말투를 따라요.",
  },
  {
    title: "한 명이 답하기도, 두세 명이 답하기도 해요",
    body: "몇 명이 답할지는 글마다 달라요. 여러 명일 때는 몇 분씩 사이를 두고 차례로 와요. 그 사이에 다른 회원이 댓글을 달면 남은 AI 친구는 답하지 않아요.",
  },
  {
    title: "답글에는 답하지 못해요",
    body: "AI 친구는 저마다 한 글에 한 번만 답해요. 답글을 남겨도 다시 답하지 않아요.",
  },
  {
    title: "전문 상담이 아니에요",
    body: "AI 친구의 댓글은 진단이나 치료를 대신하지 않아요. 불편한 댓글은 다른 댓글처럼 신고할 수 있어요.",
  },
];

/**
 * AI 친구 안내(009 US2-AC4, FR-006, 012 US4-AC6). 댓글의 AI 친구 표시와 글쓰기 화면의 안내가 여기로 온다.
 * 감정 색과 게임 요소는 쓰지 않는다.
 */
export default function AiFriendsPage() {
  return (
    <div className="flex w-full max-w-xl flex-col gap-8">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-heading text-fg font-bold">AI 친구</h1>
          <p className="text-body-sm text-fg-subtle mt-1 break-keep">
            아무도 답하지 않은 글이 혼자 남지 않게 먼저 말을 거는 AI예요.
          </p>
        </div>
        <Button asChild variant="ghost" size="sm">
          <Link href="/home">피드로</Link>
        </Button>
      </header>

      <ul className="flex flex-col gap-5">
        {FACTS.map((fact) => (
          <li key={fact.title}>
            <h2 className="text-body text-fg font-bold">{fact.title}</h2>
            <p className="text-body-sm text-fg-muted mt-1 break-keep">
              {fact.body}
            </p>
          </li>
        ))}
      </ul>

      <section
        aria-labelledby="ai-friends-names"
        className="rounded-surface bg-surface-sunken flex flex-col gap-3 p-5"
      >
        <h2 id="ai-friends-names" className="text-body text-fg font-bold">
          세 친구가 있어요
        </h2>
        <dl className="flex flex-col gap-3">
          {FRIENDS.map((friend) => (
            <div key={friend.name}>
              <dt className="text-body-sm text-fg font-bold">{friend.name}</dt>
              <dd className="text-body-sm text-fg-muted break-keep">
                {friend.voice}
              </dd>
            </div>
          ))}
        </dl>
      </section>

      <p className="text-body-sm text-fg-muted break-keep">
        지금 많이 힘들다면 혼자 견디지 마세요.{" "}
        <a
          href="tel:109"
          className="text-fg inline-flex min-h-11 items-center font-bold underline underline-offset-4"
        >
          자살예방 상담전화 109
        </a>
        는 24시간 받아요.
      </p>
    </div>
  );
}
