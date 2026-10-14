import type { Metadata } from "next";

/** 검색 결과와 공유 미리보기에 보이는 한 줄 소개. 서비스가 무엇인지 검색어가 들어가게 쓴다. */
export const SERVICE_DESCRIPTION =
  "익명으로 고민을 남기면 AI가 감정을 읽어 몬스터로 만들어요. 공감과 댓글로 함께 물리치는 감정 공유 커뮤니티, 오구오구.";

/** 네이버 서치어드바이저의 사이트 소유 확인값. 모든 화면의 head에 들어간다. */
export const NAVER_SITE_VERIFICATION =
  "4ceb3f333dd60e153d14cfc07a29041a3f555728";

/**
 * 루트 레이아웃의 메타데이터. 공유 이미지(`opengraph-image.png`)의 주소는 서비스 주소를 기준으로
 * 절대 경로가 되어야 미리보기에 뜬다. 서비스 주소는 배포마다 달라 요청을 받을 때 넘겨받는다.
 * 파비콘은 그림만 바뀌고 이름은 그대로라 주소에 판을 붙인다.
 */
export function rootMetadata(appOrigin: string): Metadata {
  return {
    metadataBase: new URL(appOrigin),
    title: { default: "오구오구", template: "%s | 오구오구" },
    description: SERVICE_DESCRIPTION,
    keywords: [
      "오구오구",
      "고민 상담",
      "감정 공유",
      "익명 커뮤니티",
      "감정 몬스터",
      "직장인 고민",
    ],
    appleWebApp: {
      capable: true,
      title: "오구오구",
      statusBarStyle: "default",
    },
    icons: {
      icon: "/icon.png?v=2",
      apple: "/brand/ogu-logo-v2-apple-touch-180.png",
    },
    openGraph: {
      type: "website",
      siteName: "오구오구",
      locale: "ko_KR",
      title: "오구오구",
      description: SERVICE_DESCRIPTION,
    },
    verification: {
      other: { "naver-site-verification": NAVER_SITE_VERIFICATION },
    },
  };
}
