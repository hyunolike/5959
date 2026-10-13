import type { Metadata } from "next";
import { PostDetail, PostNotFound } from "@/widgets/post-detail";

import { parsePostId } from "./post-id";

export const metadata: Metadata = { title: "고민 글" };

export default async function PostPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const postId = parsePostId(id);

  return postId === null ? <PostNotFound /> : <PostDetail postId={postId} />;
}
