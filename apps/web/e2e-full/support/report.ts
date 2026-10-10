import { runSql } from "./db";

const DAY_MS = 24 * 60 * 60 * 1000;
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** 지난주의 월요일(한국 시간)을 `YYYY-MM-DD`로. 리포트 화면의 주소에 쓴다. */
export function lastWeekStart(now: Date = new Date()): string {
  const kst = new Date(now.getTime() + KST_OFFSET_MS);
  const sinceMonday = (kst.getUTCDay() + 6) % 7;
  const monday = new Date(kst.getTime() - (sinceMonday + 7) * DAY_MS);
  return monday.toISOString().slice(0, 10);
}

function idList(ids: number[]): string {
  if (ids.length === 0 || ids.some((id) => !Number.isInteger(id) || id <= 0)) {
    throw new Error(`글 ID가 올바르지 않습니다: ${ids.join(",")}`);
  }
  return ids.join(",");
}

/** [postIds]의 감정 분석이 모두 끝났거나(가짜 분석기) 끝나지 않을 글만 남았을 때까지 기다린다. */
export async function waitForAnalysis(postIds: number[], analyzed: number) {
  const ids = idList(postIds);
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const count = Number(
      runSql(
        `select count(*) from emotion_analysis where post_id in (${ids}) and status = 'ANALYZED'`,
      ).trim(),
    );
    if (count >= analyzed) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`감정 분석이 끝나지 않았습니다: ${ids}`);
}

/**
 * [postIds]와 그 글의 공감, 댓글을 한 주 앞으로 옮기고 지난주 리포트 만들기를 다시 돌게 한다. 지난주의 글은 화면으로
 * 만들 수 없어 때를 직접 옮긴다. e2e 프로필의 API는 5초마다 지난주 리포트가 없는 회원을 채운다.
 *
 * 한 문장으로 보내 한 트랜잭션에서 옮긴다. 글만 옮겨진 틈에 리포트가 만들어지지 않게 한다.
 */
export function moveToLastWeek(postIds: number[]): void {
  const ids = idList(postIds);
  runSql(
    `update posts set created_at = created_at - interval '7 days' where id in (${ids}); ` +
      `update post_likes set created_at = created_at - interval '7 days' where post_id in (${ids}); ` +
      `update comments set created_at = created_at - interval '7 days' where post_id in (${ids}); ` +
      "delete from weekly_report_run",
  );
}
