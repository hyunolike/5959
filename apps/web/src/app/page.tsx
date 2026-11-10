import type { Metadata } from "next";

import { env } from "@/shared/config";
import { Landing } from "@/widgets/landing";

import { SERVICE_DESCRIPTION } from "./root-metadata";

/**
 * 첫 화면은 검색 결과에 올리는 유일한 소개 화면이다. 제목은 서비스 이름 뒤에 하는 일을 붙이고,
 * 대표 주소(canonical)를 `/`로 못 박아 옛 주소나 쿼리가 붙은 주소와 겹치지 않게 한다.
 */
export const metadata: Metadata = {
  title: {
    absolute: "오구오구 | 고민을 나누고 감정 몬스터를 함께 이겨내는 곳",
  },
  description: SERVICE_DESCRIPTION,
  alternates: { canonical: "/" },
  openGraph: { url: "/" },
};

/**
 * 점검 중이면 첫 화면에 이사 안내를 띄운다. 다른 화면은 `proxy.ts`가 이리로 보내고, 공유받은 글에서
 * 왔으면 `?from=post`를 붙인다.
 */
export default async function LandingPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const maintenance = env.OGU_MAINTENANCE_MODE
    ? { fromPost: (await searchParams).from === "post" }
    : undefined;
  return <Landing maintenance={maintenance} />;
}
