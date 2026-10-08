import { describe, expect, it } from "vitest";

import { parseMyActivityTab, toMyActivitySearch } from "./tab";

describe("parseMyActivityTab", () => {
  it.each(["posts", "comments", "likes"] as const)(
    "?tab=%s면 그 탭이다",
    (tab) => {
      expect(parseMyActivityTab(tab)).toBe(tab);
    },
  );

  it.each([null, "", "POSTS", "liked", "1"])(
    "없거나 모르는 값(%s)이면 내가 쓴 글이다",
    (value) => {
      expect(parseMyActivityTab(value)).toBe("posts");
    },
  );
});

describe("toMyActivitySearch", () => {
  it("기본 탭이면 검색어가 비어 있고, 나머지는 ?tab=으로 쓴다", () => {
    expect(toMyActivitySearch("posts")).toBe("");
    expect(toMyActivitySearch("comments")).toBe("?tab=comments");
    expect(toMyActivitySearch("likes")).toBe("?tab=likes");
  });
});
