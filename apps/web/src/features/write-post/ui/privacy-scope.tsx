import { cn } from "@/shared/lib";

const ROWS = [
  {
    who: "회원에게",
    what: "닉네임, 직군과 경력, 글 전체가 보여요.",
  },
  {
    who: "로그인하지 않은 방문자에게",
    what: "글의 앞 50자까지만 닉네임 없이 보여요.",
  },
  {
    who: "누구에게도",
    what: "이름, 이메일, 회사는 보이지 않아요.",
  },
];

/**
 * 이 글이 누구에게 무엇이 보이는지 한곳에 모은 묶음(016 FR-001~FR-003). 쓰기와 고치기 화면이 입력란
 * 위에 그린다. 첫 화면의 약속("닉네임만 남고 이름은 아무도 몰라요")과 같은 뜻으로 쓰되, 온보딩의
 * 라벨을 따라 "연차"가 아니라 "경력"이라 한다. 50자가 안 되는 글은 전체가 보인다(011 US3-AC1).
 */
export function PrivacyScope({ className }: { className?: string }) {
  return (
    <section
      aria-labelledby="privacy-scope-title"
      className={cn(
        "rounded-surface bg-surface-sunken flex flex-col gap-2 px-4 py-3.5",
        className,
      )}
    >
      <h2 id="privacy-scope-title" className="text-body-sm text-fg font-bold">
        이 글은 이렇게 보여요
      </h2>
      <ul className="text-body-sm flex flex-col gap-1.5 leading-6">
        {ROWS.map((row) => (
          <li key={row.who} className="break-keep">
            <span className="text-fg font-bold">{row.who}</span>{" "}
            <span className="text-fg-muted">{row.what}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
