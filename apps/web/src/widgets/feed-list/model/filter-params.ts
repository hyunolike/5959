import {
  CAREER_YEAR_LABELS,
  JOB_ROLE_LABELS,
  type CareerYear,
  type JobRole,
} from "@/entities/member";
import type { FeedFilter, FeedOrder } from "@/entities/post";

/** 선택지 표시 순서이자 직렬화 순서. 라벨 객체의 키 순서(계약의 enum 순서)를 따른다. */
export const JOB_ROLES = Object.keys(JOB_ROLE_LABELS) as JobRole[];
export const CAREER_YEARS = Object.keys(CAREER_YEAR_LABELS) as CareerYear[];
const ORDERS: readonly FeedOrder[] = ["LATEST", "POPULAR"];

export const DEFAULT_FEED_FILTER: FeedFilter = {
  order: "LATEST",
  jobRoles: [],
  careerYears: [],
};

/** 고른 값만 목록 순서대로, 한 번씩 남긴다. 모르는 값은 버린다. */
function pick<T extends string>(all: readonly T[], values: readonly string[]) {
  return all.filter((value) => values.includes(value));
}

/**
 * 주소의 검색어를 피드 필터로 읽는다. 주소에 두면 새로고침이나 뒤로 가기에도 고른
 * 정렬과 필터가 남는다. 이름은 API와 같다(`order`, `jobRole`, `careerYear`).
 */
export function parseFeedFilter(params: URLSearchParams): FeedFilter {
  const order = params.get("order");
  return {
    order: ORDERS.find((value) => value === order) ?? "LATEST",
    jobRoles: pick(JOB_ROLES, params.getAll("jobRole")),
    careerYears: pick(CAREER_YEARS, params.getAll("careerYear")),
  };
}

/** 피드 필터를 주소 검색어로 쓴다. 기본값(최신순, 필터 없음)이면 비어 있다. */
export function toFeedSearchParams(filter: FeedFilter): URLSearchParams {
  const params = new URLSearchParams();
  if (filter.order !== "LATEST") {
    params.set("order", filter.order);
  }
  pick(JOB_ROLES, filter.jobRoles).forEach((jobRole) =>
    params.append("jobRole", jobRole),
  );
  pick(CAREER_YEARS, filter.careerYears).forEach((careerYear) =>
    params.append("careerYear", careerYear),
  );
  return params;
}

/** 다중 선택: 없으면 넣고 있으면 뺀다. */
export function toggleValue<T>(values: readonly T[], value: T): T[] {
  return values.includes(value)
    ? values.filter((item) => item !== value)
    : [...values, value];
}

/**
 * 쪽들을 한 목록으로 펼치되 같은 글은 처음 나온 자리에만 둔다. 인기순은 쪽 사이에 공감 수가
 * 바뀌면 같은 글이 다음 쪽에 또 올 수 있다(키셋 페이지네이션의 한계, research R7).
 */
export function uniqueItems<T extends { postId: number }>(
  pages: readonly { items: readonly T[] }[],
): T[] {
  const seen = new Set<number>();
  return pages
    .flatMap((page) => page.items)
    .filter((item) => {
      if (seen.has(item.postId)) {
        return false;
      }
      seen.add(item.postId);
      return true;
    });
}
