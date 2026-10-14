import type { MetadataRoute } from "next";

import { env } from "@/shared/config";

/** 서비스 주소는 배포마다 다르므로 요청을 받을 때 환경 변수에서 읽는다. */
export const dynamic = "force-dynamic";

/** 색인해도 되는 화면만 싣는다. 홈과 글은 회원의 글이 보이는 자리라 넣지 않는다(011 US2-AC6). */
export const INDEXABLE_PATHS: { path: string; priority: number }[] = [
  { path: "/", priority: 1 },
  { path: "/ai-friends", priority: 0.5 },
];

export default function sitemap(): MetadataRoute.Sitemap {
  return INDEXABLE_PATHS.map(({ path, priority }) => ({
    url: new URL(path, env.APP_ORIGIN).toString(),
    changeFrequency: "monthly",
    priority,
  }));
}
