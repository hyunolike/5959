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
        src: "/pwa/icon-192.png",
        sizes: "192x192",
        type: "image/png",
        purpose: "any",
      },
      {
        src: "/pwa/icon-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "any",
      },
      // 기기가 원이나 둥근 사각형으로 잘라 쓰는 아이콘. 얼굴을 가운데 안전 영역에 뒀다.
      {
        src: "/pwa/icon-maskable-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  };
}
