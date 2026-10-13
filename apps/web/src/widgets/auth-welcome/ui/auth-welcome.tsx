import Image from "next/image";
import type { ReactNode } from "react";

export function AuthWelcome({ children }: { children: ReactNode }) {
  return (
    <div className="grid w-full max-w-[900px] items-center gap-12 md:grid-cols-[1fr_384px] lg:gap-20">
      <section aria-label="오구오구 소개" className="hidden md:block">
        <h2 className="text-display text-fg font-bold break-keep">
          마음을 나누는
          <br />
          오구오구
        </h2>
        <p className="text-body-sm text-fg-subtle mt-4 leading-7">
          말하기 어려웠던 고민도 괜찮아요.
          <br />
          여기서는 같은 마음을 만나 함께 이겨낼 수 있어요.
        </p>
        <Image
          src="/brand/ogu-hug.webp"
          alt="불안 몬스터를 다정하게 안아 주는 오구"
          width={280}
          height={280}
          className="mt-7 size-70"
        />
      </section>
      {children}
    </div>
  );
}
