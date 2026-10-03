import { PostDetail, PostNotFound } from "@/widgets/post-detail";

/** 글 ID는 양의 정수다(API `PostId` 경로 변수). 아니면 API를 부르지 않고 바로 안내한다. */
function parsePostId(raw: string): number | null {
  if (!/^[1-9]\d{0,17}$/.test(raw)) {
    return null;
  }
  const postId = Number(raw);
  return Number.isSafeInteger(postId) ? postId : null;
}

export default async function PostPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const postId = parsePostId(id);

  return postId === null ? <PostNotFound /> : <PostDetail postId={postId} />;
}
