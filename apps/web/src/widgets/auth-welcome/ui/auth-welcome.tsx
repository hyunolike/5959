import { Heart, MessageCircle, Sprout } from "lucide-react";
import Image from "next/image";
import type { ReactNode } from "react";

export function AuthWelcome({ children }: { children: ReactNode }) {
  return (
    <div className="grid w-full max-w-[1000px] items-center gap-6 md:grid-cols-[1.15fr_1fr] md:gap-16">
      <section aria-label="오구오구 소개" className="text-center md:text-left">
        <span className="bg-brand-50 text-brand-700 inline-flex items-center gap-2 rounded-full px-4 py-2 text-xs font-medium">
          <Sprout size={16} aria-hidden />
          마음이 쉬어가는 작은 공간
        </span>
        <h2 className="mt-4 text-[28px] leading-[1.4] font-bold tracking-[-0.06em] text-neutral-900 sm:mt-6 sm:text-[42px]">
          오늘의 마음,
          <br />
          혼자 담아두지 말아요.
        </h2>
        <p className="mt-3 text-sm leading-6 text-neutral-600 sm:mt-4 sm:text-base sm:leading-7">
          말하기 어려웠던 고민도 괜찮아요.
          <br />
          여기서는 같은 마음을 만나 함께 이겨낼 수 있어요.
        </p>
        <Image
          src="/brand/ogu-companion.png"
          alt="작은 보라색 감정 몬스터를 다정하게 안아주는 오구"
          width={470}
          height={390}
          priority
          className="mx-auto mt-6 hidden h-[320px] w-full object-contain md:block"
        />
        <div className="mt-5 hidden items-center gap-5 text-xs text-neutral-600 md:flex">
          <span className="inline-flex items-center gap-1.5">
            <MessageCircle size={15} aria-hidden />
            솔직한 이야기
          </span>
          <span className="inline-flex items-center gap-1.5">
            <Heart size={15} aria-hidden />
            따뜻한 공감
          </span>
        </div>
      </section>
      {children}
    </div>
  );
}
