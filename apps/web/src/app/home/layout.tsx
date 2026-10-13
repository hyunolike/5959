import type { Metadata } from "next";

// 페이지가 클라이언트 컴포넌트라 제목을 직접 내보낼 수 없어 여기에 둔다.
// 로그인 없이 회원의 글이 보이는 자리라 검색 엔진이 색인하지 않게 한다(011 US2-AC6).
export const metadata: Metadata = {
  title: "마음 이야기",
  robots: { index: false, follow: false },
};

export default function HomeLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return children;
}
