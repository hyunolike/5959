"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { MyCommentItem, useMyCommentsQuery } from "@/entities/comment";
import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import { EMOTION_LABELS, MonsterDisplay } from "@/entities/monster";
import {
  PostCard,
  useLikedPostsQuery,
  useMyPostsQuery,
  type FeedItem,
} from "@/entities/post";
import {
  useWeeklyReportsQuery,
  WeeklyReportItem,
} from "@/entities/weekly-report";
import { cn } from "@/shared/lib";

import {
  MY_ACTIVITY_TABS,
  parseMyActivityTab,
  toMyActivitySearch,
  type MyActivityTab,
} from "../model/tab";
import { ActivityList } from "./activity-list";

const TAB_ID = (tab: MyActivityTab) => `my-activity-tab-${tab}`;
const PANEL_ID = "my-activity-panel";

/**
 * 마이페이지 활동(004 US3): 내가 쓴 글, 내 댓글, 공감한 글을 탭으로 나눠 보인다.
 * 고른 탭은 주소의 `?tab=`에 둔다. 새로고침이나 뒤로 가기에도 남고, 모르는 값이면 "내가 쓴 글"이다.
 * 탭 내용은 고른 것만 그리므로 목록도 그 탭을 열 때만 받는다.
 */
export function MyActivity() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const active = parseMyActivityTab(searchParams.get("tab"));

  const select = (tab: MyActivityTab) => {
    if (tab !== active) {
      router.replace(`${pathname}${toMyActivitySearch(tab)}`, {
        scroll: false,
      });
    }
  };

  return (
    <section aria-label="내 활동" className="flex w-full flex-col gap-4">
      <div
        role="tablist"
        aria-label="내 활동"
        className="flex gap-1 border-b border-neutral-200"
      >
        {MY_ACTIVITY_TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={TAB_ID(tab.id)}
            aria-selected={tab.id === active}
            aria-controls={PANEL_ID}
            className={cn(
              "-mb-px border-b-2 px-3 py-2 text-sm focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none",
              tab.id === active
                ? "border-neutral-900 font-semibold text-neutral-900"
                : "border-transparent text-neutral-500 hover:text-neutral-800",
            )}
            onClick={() => select(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </div>
      <div
        role="tabpanel"
        id={PANEL_ID}
        aria-labelledby={TAB_ID(active)}
        className="flex flex-col gap-3"
      >
        {active === "posts" ? <MyPostsTab /> : null}
        {active === "comments" ? <MyCommentsTab /> : null}
        {active === "likes" ? <LikedPostsTab /> : null}
        {active === "reports" ? <WeeklyReportsTab /> : null}
      </div>
    </section>
  );
}

function renderPost(item: FeedItem) {
  return (
    <PostCard
      item={item}
      showCreatedAt
      authorMeta={`${JOB_ROLE_LABELS[item.author.jobRole]} · ${CAREER_YEAR_LABELS[item.author.careerYear]}`}
      renderMonster={(monster) => (
        <MonsterDisplay monster={monster} variant="card" />
      )}
    />
  );
}

function MyPostsTab() {
  return (
    <ActivityList
      query={useMyPostsQuery()}
      label="내가 쓴 글"
      emptyMessage="아직 쓴 글이 없어요."
      keyOf={(item) => item.postId}
      renderItem={renderPost}
    />
  );
}

function MyCommentsTab() {
  return (
    <ActivityList
      query={useMyCommentsQuery()}
      label="내 댓글"
      emptyMessage="아직 남긴 댓글이 없어요."
      keyOf={(comment) => comment.commentId}
      renderItem={(comment) => <MyCommentItem comment={comment} />}
    />
  );
}

function LikedPostsTab() {
  return (
    <ActivityList
      query={useLikedPostsQuery()}
      label="공감한 글"
      emptyMessage="아직 공감한 글이 없어요."
      keyOf={(item) => item.postId}
      renderItem={renderPost}
    />
  );
}

/** 그동안 받은 주간 리포트(008 US4). 최신 주부터 보이고, 누르면 그 주의 리포트로 간다. */
function WeeklyReportsTab() {
  return (
    <ActivityList
      query={useWeeklyReportsQuery()}
      label="주간 리포트"
      emptyMessage="아직 받은 리포트가 없어요. 글을 쓰면 다음 월요일에 지난주 리포트가 와요."
      keyOf={(report) => Date.parse(report.weekStart)}
      renderItem={(report) => (
        <WeeklyReportItem
          report={report}
          topEmotionLabel={
            report.topEmotion ? EMOTION_LABELS[report.topEmotion] : null
          }
        />
      )}
    />
  );
}
