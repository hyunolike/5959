import { describe, expect, it } from "vitest";

import {
  DEFAULT_FEED_FILTER,
  parseFeedFilter,
  toFeedSearchParams,
  toggleValue,
  uniqueItems,
} from "./filter-params";

describe("parseFeedFilter", () => {
  it("검색어가 없으면 최신순, 필터 없음이다", () => {
    expect(parseFeedFilter(new URLSearchParams())).toEqual(DEFAULT_FEED_FILTER);
    expect(DEFAULT_FEED_FILTER).toEqual({
      order: "LATEST",
      jobRoles: [],
      careerYears: [],
    });
  });

  it("US2-AC3 order=POPULAR면 인기순이다", () => {
    expect(parseFeedFilter(new URLSearchParams("order=POPULAR")).order).toBe(
      "POPULAR",
    );
  });

  it("US2-AC4 직군과 경력을 여러 개 읽는다", () => {
    const filter = parseFeedFilter(
      new URLSearchParams(
        "jobRole=DESIGN&jobRole=DEVELOPMENT&careerYear=YEAR_1&careerYear=YEAR_3",
      ),
    );

    expect(filter.jobRoles).toEqual(["DESIGN", "DEVELOPMENT"]);
    expect(filter.careerYears).toEqual(["YEAR_1", "YEAR_3"]);
  });

  it("모르는 값은 버리고, 같은 값은 한 번만 두고, 목록 순서로 정렬한다", () => {
    const filter = parseFeedFilter(
      new URLSearchParams(
        "order=OLDEST&jobRole=DEVELOPMENT&jobRole=ASTRONAUT&jobRole=PLANNING&jobRole=DEVELOPMENT&careerYear=YEAR_99",
      ),
    );

    expect(filter).toEqual({
      order: "LATEST",
      jobRoles: ["PLANNING", "DEVELOPMENT"],
      careerYears: [],
    });
  });
});

describe("toFeedSearchParams", () => {
  it("기본값(최신순, 필터 없음)이면 검색어가 비어 있다", () => {
    expect(toFeedSearchParams(DEFAULT_FEED_FILTER).toString()).toBe("");
  });

  it("정렬과 필터를 API와 같은 이름으로 직렬화한다", () => {
    const params = toFeedSearchParams({
      order: "POPULAR",
      jobRoles: ["DEVELOPMENT", "DESIGN"],
      careerYears: ["YEAR_3", "NEWCOMER"],
    });

    expect(params.toString()).toBe(
      "order=POPULAR&jobRole=DESIGN&jobRole=DEVELOPMENT&careerYear=NEWCOMER&careerYear=YEAR_3",
    );
  });

  it("직렬화한 뒤 되돌리면 같은 필터다", () => {
    const filter = {
      order: "POPULAR",
      jobRoles: ["PLANNING", "HR"],
      careerYears: ["YEAR_7_PLUS"],
    } as const;

    expect(parseFeedFilter(toFeedSearchParams(filter))).toEqual(filter);
  });
});

describe("toggleValue", () => {
  it("없으면 넣고 있으면 뺀다", () => {
    expect(toggleValue(["DESIGN"], "HR")).toEqual(["DESIGN", "HR"]);
    expect(toggleValue(["DESIGN", "HR"], "DESIGN")).toEqual(["HR"]);
  });
});

describe("uniqueItems", () => {
  it("US2-AC2 쪽들을 펼치며 같은 글은 처음 나온 자리에만 둔다", () => {
    const item = (postId: number, label: string) => ({ postId, label });

    expect(
      uniqueItems([
        { items: [item(3, "첫 쪽"), item(2, "첫 쪽")] },
        { items: [item(3, "둘째 쪽"), item(1, "둘째 쪽")] },
      ]),
    ).toEqual([item(3, "첫 쪽"), item(2, "첫 쪽"), item(1, "둘째 쪽")]);
  });
});
