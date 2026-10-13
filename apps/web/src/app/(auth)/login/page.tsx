import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import {
  EMOTION_LABELS,
  EmotionTag,
  type EmotionType,
} from "@/entities/monster";
import { EmailLoginDisclosure } from "@/features/auth/email-login";
import {
  OAuthButtons,
  OAuthLoginErrorNotice,
} from "@/features/auth/oauth-buttons";
import { env } from "@/shared/config";
import { withNextPath } from "@/shared/lib";
import { AuthWelcome } from "@/widgets/auth-welcome";

const EMOTIONS = Object.keys(EMOTION_LABELS) as EmotionType[];

function firstValue(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export const metadata: Metadata = { title: "로그인" };

/**
 * 로그인 첫 화면(스펙 "로그인 첫 화면"). 오구와 서비스 소개가 먼저 오고, 그 아래에 카카오, 구글,
 * 이메일 순으로 들어가는 길을 둔다. 이메일 입력란은 누르면 펼쳐진다.
 * 화면의 제목(`h1`)은 문서 제목과 같은 "로그인"이고 읽히기만 한다. 보이는 머리 문구는 따로다.
 */
export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { error, next: nextParam } = await searchParams;
  const next = firstValue(nextParam);

  return (
    <AuthWelcome>
      <div className="mx-auto flex w-full max-w-sm flex-col gap-8">
        <div className="flex flex-col items-center gap-5 text-center md:items-start md:text-left">
          <h1 className="sr-only">로그인</h1>
          {/* 넓은 화면에서는 왼쪽 소개에 오구가 있다. 그림을 두 번 보이지 않는다. */}
          <span className="bg-surface-sunken rounded-surface flex size-42 items-center justify-center md:hidden">
            <Image
              src="/brand/ogu-hello.webp"
              alt=""
              width={132}
              height={132}
              priority
              className="size-33"
            />
          </span>
          <div className="flex flex-col gap-2">
            <p className="text-heading text-fg font-extrabold break-keep">
              오늘 마음은 어때요?
            </p>
            <p className="text-body text-fg-subtle break-keep">
              고민을 털어놓으면 감정 몬스터가 돼요.
              <br />
              공감과 댓글로 함께 물리쳐요.
            </p>
          </div>
          {/* 목록 모양을 지운 ul은 Safari에서 목록으로 읽히지 않는다. 역할을 적어 이름이 읽히게 한다. */}
          <ul
            role="list"
            aria-label="감정 몬스터의 종류"
            className="flex flex-wrap justify-center gap-2 md:justify-start"
          >
            {EMOTIONS.map((emotion) => (
              <li key={emotion}>
                <EmotionTag emotion={emotion} />
              </li>
            ))}
          </ul>
        </div>

        <div className="flex flex-col gap-2.5">
          <OAuthLoginErrorNotice error={error} />
          <OAuthButtons next={next} kakaoEnabled={env.KAKAO_LOGIN_ENABLED} />
          {/* "이메일과 비밀번호로 로그인해주세요"라고 알린 화면에서는 입력란을 펼쳐 둔다. */}
          <EmailLoginDisclosure
            next={next}
            defaultOpen={firstValue(error) === "email_registered"}
          />
          <p className="text-body-sm text-fg-subtle flex items-center justify-center gap-1 pt-1">
            <Link
              href="/home"
              className="text-fg inline-flex min-h-11 items-center px-2.5 font-bold hover:underline"
            >
              먼저 둘러보기
            </Link>
            <span aria-hidden className="bg-line-strong h-3 w-px" />
            <Link
              href={withNextPath("/signup", next)}
              className="text-fg inline-flex min-h-11 items-center px-2.5 font-bold hover:underline"
            >
              회원가입
            </Link>
          </p>
        </div>
      </div>
    </AuthWelcome>
  );
}
