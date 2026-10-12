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
        src: "/pwa/icon-192.svg",
        sizes: "192x192",
        type: "image/svg+xml",
        purpose: "any",
      },
      {
        src: "/pwa/icon-512.svg",
        sizes: "512x512",
        type: "image/svg+xml",
        purpose: "any",
      },
    ],
  };
}
