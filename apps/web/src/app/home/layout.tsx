import type { Metadata } from "next";

// 페이지가 클라이언트 컴포넌트라 제목을 직접 내보낼 수 없어 여기에 둔다.
export const metadata: Metadata = { title: "마음 이야기" };

export default function HomeLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return children;
}
