"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";

import { Button } from "@/shared/ui";

import { LoginForm } from "./login-form";

/**
 * 로그인 첫 화면의 "이메일로 로그인". 접힌 채로 시작하고, 누르면 같은 화면에서 입력란이 펼쳐진다
 * (스펙 "로그인 첫 화면"). 펼칠 때 초점을 이메일 칸으로 옮긴다. 다시 접어도 적어 둔 값은 남는다.
 * 이메일로 가입한 계정이라는 안내와 함께 돌아온 화면에서는 처음부터 펼쳐 둔다(`defaultOpen`).
 */
export function EmailLoginDisclosure({
  next,
  defaultOpen = false,
  passwordResetAvailable = false,
}: {
  next?: string;
  /** 처음부터 펼쳐 둔다. 이메일로 로그인하라는 안내와 함께 돌아왔을 때 쓴다. */
  defaultOpen?: boolean;
  /**
   * 비밀번호 재설정을 쓸 수 있으면 입력란 아래에 "비밀번호를 잊으셨나요?"를 둔다(015 US1-AC1). 메일 발송이 꺼져
   * 있으면 그리지 않는다. 눌러 봐야 안 된다는 것을 아는 길을 두지 않는다(US4-AC1).
   */
  passwordResetAvailable?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const panelId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  // 초점은 눌러서 펼쳤을 때만 옮긴다. 처음부터 펼쳐 둔 화면에서는 위의 안내를 먼저 읽게 둔다.
  const toggled = useRef(false);

  useEffect(() => {
    if (open && toggled.current) {
      panelRef.current?.querySelector("input")?.focus();
    }
  }, [open]);

  return (
    <div className="flex flex-col gap-4">
      <Button
        type="button"
        variant="secondary"
        size="lg"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          toggled.current = true;
          setOpen((current) => !current);
        }}
      >
        이메일로 로그인
      </Button>
      <div id={panelId} ref={panelRef} hidden={!open}>
        <LoginForm next={next} />
        {passwordResetAvailable ? (
          <p className="mt-2 text-center">
            <Link
              href="/forgot-password"
              className="text-body-sm text-fg-subtle hover:text-fg inline-flex min-h-11 items-center px-2 font-medium underline underline-offset-4"
            >
              비밀번호를 잊으셨나요?
            </Link>
          </p>
        ) : null}
      </div>
    </div>
  );
}
