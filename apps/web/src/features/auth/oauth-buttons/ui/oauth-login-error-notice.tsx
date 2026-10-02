import { cn } from "@/shared/lib";

const NOTICES = {
  // US3-AC4: 사용자가 스스로 취소한 것이라 오류로 다루지 않는다.
  oauth_cancelled: {
    role: "status",
    className: "text-neutral-600",
    message:
      "외부 계정 로그인을 취소했습니다. 다시 시도하거나 다른 방법으로 로그인해주세요.",
  },
  oauth_failed: {
    role: "alert",
    className: "text-red-600",
    message: "외부 계정으로 로그인하지 못했습니다. 잠시 후 다시 시도해주세요.",
  },
  // US3-AC3: 계정을 합치지 않고 원래 가입한 방법을 안내한다.
  email_registered: {
    role: "alert",
    className: "text-red-600",
    message:
      "이 이메일은 이미 이메일 가입에 쓰이고 있습니다. 이메일과 비밀번호로 로그인해주세요.",
  },
} as const;

type OAuthLoginError = keyof typeof NOTICES;

function isOAuthLoginError(value: unknown): value is OAuthLoginError {
  return typeof value === "string" && Object.hasOwn(NOTICES, value);
}

interface OAuthLoginErrorNoticeProps {
  /** `/login?error=` 값. 알 수 없는 값이면 아무것도 그리지 않는다. */
  error: string | string[] | undefined;
}

/** OAuth 콜백이 `/login?error=`로 돌려보냈을 때의 안내. */
export function OAuthLoginErrorNotice({ error }: OAuthLoginErrorNoticeProps) {
  if (!isOAuthLoginError(error)) {
    return null;
  }
  const notice = NOTICES[error];
  return (
    <p role={notice.role} className={cn("text-sm", notice.className)}>
      {notice.message}
    </p>
  );
}
