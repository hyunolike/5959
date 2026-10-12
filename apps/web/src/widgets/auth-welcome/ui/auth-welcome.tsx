import Image from "next/image";
import type { ReactNode } from "react";

export function AuthWelcome({ children }: { children: ReactNode }) {
  return (
    <div className="grid w-full max-w-[900px] items-center gap-12 md:grid-cols-[1fr_360px] lg:gap-24">
      <section aria-label="오구오구 소개" className="hidden md:block">
        <h2 className="text-[28px] leading-[1.5] font-bold tracking-[-0.04em] text-neutral-900">
          마음을 나누는
          <br />
          오구오구
        </h2>
        <p className="mt-4 text-sm leading-7 text-neutral-600">
          말하기 어려웠던 고민도 괜찮아요.
          <br />
          여기서는 같은 마음을 만나 함께 이겨낼 수 있어요.
        </p>
        <Image
          src="/brand/ogu-companion.png"
          alt="작은 보라색 감정 몬스터를 다정하게 안아주는 오구"
          width={280}
          height={231}
          className="mt-7 h-[231px] w-[280px] object-contain"
        />
      </section>
      {children}
    </div>
  );
}
