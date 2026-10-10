"use client";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import { MonsterDisplay } from "@/entities/monster";
import {
  PostCard,
  useSimilarPostsQuery,
  type SimilarPosts as SimilarPostsData,
} from "@/entities/post";

/** 무엇을 근거로 골랐는지 제목이 말한다. 같은 감정의 글을 "비슷한 고민"이라 부르지 않는다(US2-AC2). */
const HEADINGS: Record<Exclude<SimilarPostsData["basis"], "NONE">, string> = {
  SIMILAR: "비슷한 고민",
  SAME_EMOTION: "같은 감정의 고민",
};

/**
 * 글 상세 아래 추천 구역(007 US1, US2). 보여 줄 글이 없거나 불러오지 못하면 아무것도 그리지
 * 않는다. 추천이 없다고 글 읽기를 방해하지 않는다(US2-AC3, US2-AC7). 카드는 피드와 같은 것을 쓴다.
 */
export function SimilarPosts({ postId }: { postId: number }) {
  const { data } = useSimilarPostsQuery(postId);

  if (!data || data.basis === "NONE" || data.items.length === 0) {
    return null;
  }

  const headingId = `similar-posts-${postId}`;
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <h2 id={headingId} className="text-sm font-semibold text-neutral-800">
        {HEADINGS[data.basis]}
      </h2>
      <ul className="flex flex-col gap-3">
        {data.items.map((item) => (
          <li key={item.postId}>
            <PostCard
              item={item}
              authorMeta={`${JOB_ROLE_LABELS[item.author.jobRole]} · ${CAREER_YEAR_LABELS[item.author.careerYear]}`}
              renderMonster={(monster) => (
                <MonsterDisplay monster={monster} variant="card" />
              )}
            />
          </li>
        ))}
      </ul>
    </section>
  );
}
