import type { Metadata } from "next";
import { PostDetail, PostNotFound } from "@/widgets/post-detail";

import { parsePostId } from "./post-id";

export const metadata: Metadata = { title: "고민 글" };

export default async function PostPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const [{ id }, { first }] = await Promise.all([params, searchParams]);
  const postId = parsePostId(id);

  // 쓰기 화면이 첫 글을 올린 뒤 붙이는 표시다(016 research R3). 상세가 받자마자 주소에서 지운다.
  return postId === null ? (
    <PostNotFound />
  ) : (
    <PostDetail postId={postId} firstPost={first === "1"} />
  );
}
