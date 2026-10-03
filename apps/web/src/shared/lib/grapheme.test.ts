import { describe, expect, it, vi } from "vitest";

import { countGraphemes, trimmedGraphemeLength } from "./grapheme";

/**
 * 사람이 보는 글자(grapheme) 수를 센다. API의 `Grapheme.count`(BreakIterator,
 * apps/api/src/main/kotlin/com/ogu/shared/text/Grapheme.kt)와 같은 값을 내야
 * 한다. 테스트 사례는 그 파일의 GraphemeTest와 맞춘다.
 */
describe("countGraphemes", () => {
  it.each([
    ["안녕하세요", 5],
    ["hello", 5],
    ["👍", 1],
    ["👨‍👩‍👧", 1],
    ["🇰🇷", 1],
    ["오늘 👍 🇰🇷", 6],
    ["", 0],
  ])("%s는 %i글자다", (text, expected) => {
    expect(countGraphemes(text)).toBe(expected);
  });

  it("이모지 500개는 500글자다", () => {
    expect(countGraphemes("👨‍👩‍👧".repeat(500))).toBe(500);
  });

  it("피부색 변형 이모지(👍🏽)는 1글자다", () => {
    expect(countGraphemes("👍🏽")).toBe(1);
  });

  it("키캡 이모지(1️⃣)는 1글자다", () => {
    expect(countGraphemes("1️⃣")).toBe(1);
  });

  it("분리된 한글 자모(ㅎ+ㅏ+ㄴ)는 결합돼 1글자다", () => {
    expect(countGraphemes("한")).toBe(1);
  });

  it("Intl.Segmenter가 없으면 코드 포인트 수로 대신 센다", () => {
    vi.stubGlobal("Intl", { Segmenter: undefined });
    try {
      expect(countGraphemes("hello")).toBe(5);
      expect(countGraphemes("안녕")).toBe(2);
      // 폴백은 코드 포인트 단위라 결합 이모지(ZWJ 시퀀스)는 조각으로 센다.
      expect(countGraphemes("👨‍👩‍👧")).toBeGreaterThan(1);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe("trimmedGraphemeLength", () => {
  it("앞뒤 공백을 빼고 글자 수를 센다", () => {
    expect(trimmedGraphemeLength("  안녕하세요  ")).toBe(5);
  });

  it("공백만 있으면 0이다", () => {
    expect(trimmedGraphemeLength("   ")).toBe(0);
  });

  it("빈 문자열은 0이다", () => {
    expect(trimmedGraphemeLength("")).toBe(0);
  });
});
