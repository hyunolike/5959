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
        src: "/pwa/ogu-lettering-v1-192.png",
        sizes: "192x192",
        type: "image/png",
        purpose: "any",
      },
      {
        src: "/pwa/ogu-lettering-v1-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "any",
      },
      // 글자와 전신 오구를 80% 지름 안전 원 안에 따로 배치한 버전이다.
      {
        src: "/pwa/ogu-lettering-v1-maskable-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  };
}
