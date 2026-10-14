/**
 * 닉네임 변경 제한(014). 닉네임을 한 번 바꾸면 한동안 다시 바꿀 수 없다. 서버가 프로필에 "다시 바꿀 수 있게
 * 되는 때"를 싣고, 화면은 그것을 지금과 견준다. 값이 없거나 지난 때면 바꿀 수 있다.
 */

/** 서버의 기간(`ogu.member.nickname-change.interval`)과 같은 값이다. 서버의 설정을 바꾸면 여기도 바꾼다. */
export const NICKNAME_LOCK_DAYS = 30;

/** 바꾸기 전에 보이는 안내(US2-AC1). */
export const NICKNAME_LOCK_NOTICE = `닉네임을 바꾸면 ${NICKNAME_LOCK_DAYS}일 동안 다시 바꿀 수 없어요.`;

/**
 * 지금 제한 중이면 끝나는 때를, 아니면 null을 돌려준다. 읽을 수 없는 값은 제한이 없는 것으로 본다.
 * 화면이 잘못 잠기는 것보다 서버가 거절하는 쪽이 낫다.
 */
export function nicknameLockedUntil(
  availableAt: string | null | undefined,
  now: number,
): Date | null {
  if (!availableAt) {
    return null;
  }
  const until = new Date(availableAt);
  if (Number.isNaN(until.getTime()) || until.getTime() <= now) {
    return null;
  }
  return until;
}

/**
 * 한국 시간의 월, 일, 시각을 숫자로 뽑는다. 제한은 자정이 아니라 바꾼 시각에 풀린다. 오전과 오후 같은 낱말은
 * 실행 환경의 지역 데이터에 따라 달라져(같은 `ko-KR`이 "오전"으로도 "AM"으로도 나온다) 24시간으로 적는다.
 */
const LOCK_END = new Intl.DateTimeFormat("en-US", {
  timeZone: "Asia/Seoul",
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/** 제한 중일 때 보이는 안내(US2-AC2). 예: "닉네임은 11월 13일 08:30부터 다시 바꿀 수 있어요." */
export function nicknameLockedMessage(until: Date): string {
  const parts = Object.fromEntries(
    LOCK_END.formatToParts(until).map((part) => [part.type, part.value]),
  );
  return `닉네임은 ${parts.month}월 ${parts.day}일 ${parts.hour}:${parts.minute}부터 다시 바꿀 수 있어요.`;
}
