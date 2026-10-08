/**
 * 사람이 보는 글자(grapheme) 수를 센다. 결합 이모지(👨‍👩‍👧)와 국기(🇰🇷)도 한 글자다.
 * API는 `java.text.BreakIterator`로 같은 값을 낸다
 * (apps/api/src/main/kotlin/com/ogu/shared/text/Grapheme.kt).
 *
 * `Intl.Segmenter`를 쓸 수 없는 환경(구형 브라우저, 일부 테스트 런타임)에서는
 * 코드 포인트 수로 대신 센다 — 결합 이모지처럼 여러 코드 포인트로 이뤄진
 * 글자는 조각으로 세어질 수 있지만, 본문 길이 제한을 완전히 무력화하지 않도록
 * 아예 세지 않는 것보다는 낫다.
 */
export function countGraphemes(text: string): number {
  if (text.length === 0) return 0;

  if (typeof Intl !== "undefined" && typeof Intl.Segmenter === "function") {
    const segmenter = new Intl.Segmenter("ko", { granularity: "grapheme" });
    return Array.from(segmenter.segment(text)).length;
  }

  return Array.from(text).length;
}

/** 앞뒤 공백을 뺀 글자 수. 글(1~500)과 댓글(1~300) 길이 검증에 쓴다. */
export function trimmedGraphemeLength(text: string): number {
  return countGraphemes(text.trim());
}
