import {
  ChatCircleDots,
  PencilSimpleLine,
  Robot,
  Sword,
} from "@phosphor-icons/react/dist/ssr";
import Image from "next/image";
import Link from "next/link";

import {
  EMOTION_LABELS,
  MonsterLifecycleLoop,
  MonsterSprite,
  type EmotionType,
} from "@/entities/monster";
import { Button } from "@/shared/ui";

const EMOTIONS = Object.keys(EMOTION_LABELS) as EmotionType[];

const STEPS = [
  {
    icon: PencilSimpleLine,
    title: "익명으로 고민을 적어요",
    body: "닉네임만 남고 이름은 아무도 몰라요. 직장과 일상에서 무거웠던 마음을 그대로 적으면 돼요.",
  },
  {
    icon: Sword,
    title: "고민이 감정 몬스터가 돼요",
    body: "AI가 글의 감정을 읽어 불안, 무기력, 외로움, 자기비하, 짜증 가운데 하나의 몬스터로 만들어요.",
  },
  {
    icon: ChatCircleDots,
    title: "공감과 댓글로 함께 물리쳐요",
    body: "다른 회원의 공감과 댓글이 모일수록 몬스터의 HP가 줄고, 다 떨어지면 쓰러져요.",
  },
  {
    icon: Robot,
    title: "AI 친구가 먼저 답해요",
    body: "한동안 댓글이 없으면 AI 친구가 먼저 공감을 남겨요. AI가 쓴 댓글은 언제나 표시돼요.",
  },
];

const FEATURES = [
  {
    title: "함께 이겨내기",
    body: "모두의 감정이 모여 보스 몬스터가 되면, 회원 전체가 힘을 합쳐 한 마리를 쓰러뜨려요.",
  },
  {
    title: "몬스터 도감",
    body: "내가 물리친 몬스터가 도감에 쌓여요. 어떤 감정을 자주 겪고 이겨냈는지 돌아볼 수 있어요.",
  },
  {
    title: "주간 리포트",
    body: "한 주의 감정 흐름과 받은 응원을 리포트로 받아요.",
  },
];

/**
 * 첫 화면(`/`). 로그인하지 않은 방문자와 검색 엔진이 보는 서비스 소개다(002 FR-014). 회원 데이터를
 * 부르지 않는 서버 컴포넌트라 HTML에 소개가 그대로 담긴다. 로그인한 회원은 가드가 홈으로 보낸다.
 */
export function Landing() {
  return (
    <div className="flex w-full max-w-[960px] flex-col gap-16 sm:gap-20">
      <section className="grid items-center gap-8 md:grid-cols-[1fr_300px] md:gap-12">
        <div className="flex flex-col items-center gap-6 text-center md:items-start md:text-left">
          <h1 className="text-display text-fg font-extrabold break-keep">
            고민을 나누고
            <br />
            감정 몬스터를 함께 이겨내는 오구오구
          </h1>
          <p className="text-body-lg text-fg-subtle max-w-md break-keep">
            익명으로 고민을 남기면 AI가 감정을 읽어 몬스터로 만들어요. 공감과
            댓글이 모일수록 몬스터가 약해지고, 마음은 조금 가벼워져요.
          </p>
          <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row">
            <Button asChild size="lg">
              <Link href="/login">시작하기</Link>
            </Button>
            <Button asChild size="lg" variant="secondary">
              <Link href="/home">먼저 둘러보기</Link>
            </Button>
          </div>
        </div>
        <Image
          src="/brand/ogu-hug.webp"
          alt="불안 몬스터를 다정하게 안아 주는 오구"
          width={300}
          height={300}
          priority
          className="mx-auto size-56 md:size-75"
        />
      </section>

      <section
        aria-labelledby="landing-monsters"
        className="flex flex-col gap-5"
      >
        <div>
          <h2 id="landing-monsters" className="text-heading text-fg font-bold">
            다섯 가지 감정 몬스터
          </h2>
          <p className="text-body text-fg-subtle mt-2 max-w-xl break-keep">
            고민마다 다른 감정이 몬스터로 나타나요. 혼자 끌어안고 있던 마음을
            눈앞의 캐릭터로 보면 조금은 다룰 만해져요.
          </p>
        </div>
        <ul className="grid grid-cols-3 gap-3 sm:grid-cols-5">
          {EMOTIONS.map((emotion) => (
            <li
              key={emotion}
              data-emotion={emotion}
              className="rounded-surface bg-emotion-tint flex flex-col items-center gap-2 p-4"
            >
              <span className="size-20 sm:size-24">
                <MonsterSprite emotion={emotion} stage="full" sizes="96px" />
              </span>
              <span className="text-body-sm text-emotion-fg font-bold">
                {EMOTION_LABELS[emotion]}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="landing-steps" className="flex flex-col gap-5">
        <h2 id="landing-steps" className="text-heading text-fg font-bold">
          오구오구는 이렇게 써요
        </h2>
        {/* 글로 쓴 단계 옆에서 한 바퀴를 눈으로 본다(016 US5). 첫 화면 위쪽에는 두지 않는다(SC-006). */}
        <MonsterLifecycleLoop className="mx-auto w-full max-w-md" />
        <ol className="grid gap-4 sm:grid-cols-2">
          {STEPS.map((step, index) => (
            <li
              key={step.title}
              className="rounded-surface border-line bg-surface flex gap-4 border p-5"
            >
              <span
                aria-hidden
                className="bg-surface-sunken text-fg flex size-10 shrink-0 items-center justify-center rounded-full"
              >
                <step.icon size={20} />
              </span>
              <div>
                <p className="text-body text-fg font-bold break-keep">
                  <span className="sr-only">{index + 1}단계. </span>
                  {step.title}
                </p>
                <p className="text-body-sm text-fg-subtle mt-1 break-keep">
                  {step.body}
                </p>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section
        aria-labelledby="landing-features"
        className="flex flex-col gap-5"
      >
        <h2 id="landing-features" className="text-heading text-fg font-bold">
          함께하면 더 할 수 있는 것
        </h2>
        <ul className="grid gap-4 sm:grid-cols-3">
          {FEATURES.map((feature) => (
            <li
              key={feature.title}
              className="rounded-surface bg-surface-sunken flex flex-col gap-1 p-5"
            >
              <p className="text-body text-fg font-bold">{feature.title}</p>
              <p className="text-body-sm text-fg-subtle break-keep">
                {feature.body}
              </p>
            </li>
          ))}
        </ul>
      </section>

      <section
        aria-labelledby="landing-safety"
        className="grid items-center gap-6 md:grid-cols-[200px_1fr]"
      >
        <Image
          src="/brand/ogu-listen.webp"
          alt="귀 기울여 듣는 오구"
          width={200}
          height={200}
          className="mx-auto size-40 md:size-50"
        />
        <div className="flex flex-col gap-3 text-center md:text-left">
          <h2 id="landing-safety" className="text-heading text-fg font-bold">
            안심하고 말할 수 있는 곳
          </h2>
          <p className="text-body text-fg-subtle break-keep">
            글에는 닉네임과 직군, 연차만 보여요. 로그인하지 않은 방문자에게는
            글의 앞부분만 닉네임 없이 보이고, 글 전체와 댓글은 회원만 읽을 수
            있어요. 힘든 신호가 보이는 글에는 도움을 받을 수 있는 안내를 함께
            띄워요.
          </p>
        </div>
      </section>

      <section className="rounded-surface bg-surface-sunken flex flex-col items-center gap-4 p-8 text-center">
        <h2 className="text-heading text-fg font-bold break-keep">
          오늘 마음은 어때요?
        </h2>
        <p className="text-body text-fg-subtle max-w-md break-keep">
          카카오나 구글 계정이면 바로 시작할 수 있어요. 이메일로도 가입할 수
          있어요.
        </p>
        <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row">
          <Button asChild size="lg">
            <Link href="/login">로그인하고 시작하기</Link>
          </Button>
          <Button asChild size="lg" variant="secondary">
            <Link href="/signup">회원가입</Link>
          </Button>
        </div>
      </section>
    </div>
  );
}
