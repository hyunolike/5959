import type { Metadata } from "next";

export const metadata: Metadata = { title: "회원 탈퇴" };

/** 화면은 클라이언트 컴포넌트라 제목을 여기서 준다. */
export default function WithdrawLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return children;
}
