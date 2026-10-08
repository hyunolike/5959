/** 마이페이지 활동 탭(004 US3). 주소의 `?tab=` 값이자 표시 순서다. */
export const MY_ACTIVITY_TABS = [
  { id: "posts", label: "내가 쓴 글" },
  { id: "comments", label: "내 댓글" },
  { id: "likes", label: "공감한 글" },
] as const;

export type MyActivityTab = (typeof MY_ACTIVITY_TABS)[number]["id"];

export const DEFAULT_MY_ACTIVITY_TAB: MyActivityTab = "posts";

/** 주소의 `tab` 값을 탭으로 읽는다. 없거나 모르는 값이면 "내가 쓴 글"이다. */
export function parseMyActivityTab(value: string | null): MyActivityTab {
  return (
    MY_ACTIVITY_TABS.find((tab) => tab.id === value)?.id ??
    DEFAULT_MY_ACTIVITY_TAB
  );
}

/** 탭을 주소 검색어로 쓴다. 기본 탭이면 비어 있다. */
export function toMyActivitySearch(tab: MyActivityTab): string {
  return tab === DEFAULT_MY_ACTIVITY_TAB ? "" : `?tab=${tab}`;
}
