import { Button } from "@/shared/ui";

const PROVIDERS = [
  { id: "kakao", label: "카카오로 계속하기" },
  { id: "google", label: "구글로 계속하기" },
] as const;

interface OAuthButtonsProps {
  /** 로그인 뒤 돌아갈 경로. 검증은 서버(시작 라우트)가 한다. */
  next?: string;
}

/**
 * 외부 계정 로그인 버튼. 자바스크립트 없이도 동작하는 일반 링크로, BFF의
 * OAuth 시작 라우트(`/api/auth/oauth/{provider}`)로 이동한다. 시작 라우트가
 * state 쿠키를 심고 제공자로 보낸다.
 */
export function OAuthButtons({ next = "/home" }: OAuthButtonsProps) {
  const query = `next=${encodeURIComponent(next)}`;
  return (
    <div className="flex flex-col gap-2">
      {PROVIDERS.map(({ id, label }) => (
        <Button key={id} asChild variant="outline">
          <a href={`/api/auth/oauth/${id}?${query}`}>{label}</a>
        </Button>
      ))}
    </div>
  );
}
