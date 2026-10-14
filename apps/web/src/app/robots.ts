import type { MetadataRoute } from "next";

import { env } from "@/shared/config";

/** 서비스 주소는 배포마다 다르므로 요청을 받을 때 환경 변수에서 읽는다. */
export const dynamic = "force-dynamic";

/**
 * 로그인해야 열리는 경로와 BFF는 긁지 않게 한다. 로그인, 가입 화면은 긁어도 되지만 각 화면의
 * `robots: noindex`로 색인하지 않고, 홈(`/home`)도 같은 방식이라 여기서는 막지 않는다. 막아 버리면
 * 크롤러가 `noindex`를 읽지 못해 주소만 색인에 남을 수 있다.
 */
export const DISALLOWED_PATHS = [
  "/api/",
  "/write",
  "/post/",
  "/my",
  "/settings",
  "/notifications",
  "/raid",
  "/report/",
  "/cards",
  "/onboarding",
  "/withdraw",
  "/debug/",
];

export default function robots(): MetadataRoute.Robots {
  return {
    rules: { userAgent: "*", allow: "/", disallow: DISALLOWED_PATHS },
    sitemap: `${env.APP_ORIGIN}/sitemap.xml`,
  };
}
