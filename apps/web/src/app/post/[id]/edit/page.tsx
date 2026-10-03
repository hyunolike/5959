import { PostNotFound } from "@/widgets/post-detail";
import { PostEditorCard } from "@/widgets/post-editor";

import { parsePostId } from "../post-id";

/** 글 고치기(US4-AC1). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다. */
export default async function EditPostPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const postId = parsePostId(id);

  return postId === null ? (
    <PostNotFound />
  ) : (
    <PostEditorCard postId={postId} />
  );
}
