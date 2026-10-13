import { describe, expect, it } from "vitest";

import { rootMetadata } from "./root-metadata";

describe("루트 메타데이터", () => {
  it("LOGO-AC5 공유 이미지의 주소는 서비스 주소를 기준으로 절대 경로가 된다", () => {
    const metadata = rootMetadata("https://ogu.example");
    expect(String(metadata.metadataBase)).toBe("https://ogu.example/");
    expect(metadata.openGraph).toMatchObject({
      siteName: "오구오구",
      locale: "ko_KR",
    });
  });

  it("LOGO-AC2 파비콘과 애플 아이콘은 판을 올린 주소다", () => {
    const { icons } = rootMetadata("https://ogu.example");
    // 파비콘은 그림만 바뀌고 이름은 그대로라 주소에 판을 붙인다.
    expect(icons).toEqual({
      icon: "/icon.png?v=2",
      apple: "/brand/ogu-logo-v2-apple-touch-180.png",
    });
  });

  it("DS-US4-AC1 제목은 경로의 제목 뒤에 서비스 이름을 붙인다", () => {
    expect(rootMetadata("https://ogu.example").title).toEqual({
      default: "오구오구",
      template: "%s | 오구오구",
    });
  });
});
