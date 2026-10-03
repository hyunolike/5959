/** 글 ID는 양의 정수다(API `PostId` 경로 변수). 아니면 API를 부르지 않고 바로 안내한다. */
export function parsePostId(raw: string): number | null {
  if (!/^[1-9]\d{0,17}$/.test(raw)) {
    return null;
  }
  const postId = Number(raw);
  return Number.isSafeInteger(postId) ? postId : null;
}
