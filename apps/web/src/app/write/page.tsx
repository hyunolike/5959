import type { Metadata } from "next";
import Link from "next/link";

import { WriteForm } from "@/features/write-post";
import { AI_FRIENDS_PATH } from "@/shared/ui";

/** 고민 글쓰기(US1). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다(US1-AC7). */
export const metadata: Metadata = { title: "고민 쓰기" };

export default function WritePage() {
  return (
    <div className="flex w-full max-w-xl flex-col gap-5">
      <header>
        <h1 className="text-heading text-fg font-bold">고민 쓰기</h1>
        <p className="text-body-sm text-fg-subtle mt-1 leading-6">
          털어놓으면 고민이 몬스터가 돼요. 회원들의 공감과 댓글로 함께 물리쳐요.
        </p>
        {/* AI의 댓글을 받기 전에 미리 알린다(009 research R13). */}
        <p className="text-body-sm text-fg-subtle leading-6">
          한동안 댓글이 없으면{" "}
          <Link
            href={AI_FRIENDS_PATH}
            className="text-fg font-bold underline underline-offset-4"
          >
            AI 친구
          </Link>
          가 먼저 답할 수 있어요.
        </p>
        {/* 글이 어디까지 보이는지 쓰기 전에 알린다(011 US3-AC1). 50자가 안 되는 글은 전체가 보인다. */}
        <p className="text-body-sm text-fg-subtle leading-6">
          올린 글의 앞 50자는 로그인하지 않은 방문자에게도 닉네임 없이 보여요.
        </p>
      </header>
      <WriteForm />
    </div>
  );
}
