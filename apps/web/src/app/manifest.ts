import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    id: "/",
    name: "오구오구",
    short_name: "오구오구",
    description: "고민을 나누고 함께 공감하는 오구오구",
    start_url: "/home",
    scope: "/",
    display: "standalone",
    lang: "ko",
    background_color: "#ffffff",
    theme_color: "#ffffff",
    prefer_related_applications: false,
    icons: [
      {
        src: "/pwa/ogu-logo-v2-192.png",
        sizes: "192x192",
        type: "image/png",
        purpose: "any",
      },
      {
        src: "/pwa/ogu-logo-v2-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "any",
      },
      // 심볼이 가운데 안전 영역(지름 80% 원) 안에 든 버전이다. 원형으로 잘려도 얼굴이 남는다.
      {
        src: "/pwa/ogu-logo-v2-maskable-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  };
}
