import type { Metadata } from "next";

/**
 * 루트 레이아웃의 메타데이터. 공유 이미지(`opengraph-image.png`)의 주소는 서비스 주소를 기준으로
 * 절대 경로가 되어야 미리보기에 뜬다. 서비스 주소는 배포마다 달라 요청을 받을 때 넘겨받는다.
 * 파비콘은 그림만 바뀌고 이름은 그대로라 주소에 판을 붙인다.
 */
export function rootMetadata(appOrigin: string): Metadata {
  return {
    metadataBase: new URL(appOrigin),
    title: { default: "오구오구", template: "%s | 오구오구" },
    description: "감정을 나누고 함께 이겨내는 서비스",
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
      description: "고민을 나누고 감정 몬스터를 함께 이겨내는 곳",
    },
  };
}
