import { describe, expect, it } from "vitest";

import { metadata } from "./layout";

describe("홈의 메타데이터", () => {
  it("US2-AC6 검색 엔진에 색인하지 말라고 알린다", () => {
    expect(metadata.robots).toEqual({ index: false, follow: false });
    expect(metadata.title).toBe("마음 이야기");
  });
});
