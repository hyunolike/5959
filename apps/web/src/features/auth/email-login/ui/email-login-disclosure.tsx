"use client";

import { useEffect, useId, useRef, useState } from "react";

import { Button } from "@/shared/ui";

import { LoginForm } from "./login-form";

/**
 * 로그인 첫 화면의 "이메일로 로그인". 접힌 채로 시작하고, 누르면 같은 화면에서 입력란이 펼쳐진다
 * (스펙 "로그인 첫 화면"). 펼칠 때 초점을 이메일 칸으로 옮긴다. 다시 접어도 적어 둔 값은 남는다.
 */
export function EmailLoginDisclosure({ next }: { next?: string }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) {
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
        onClick={() => setOpen((current) => !current)}
      >
        이메일로 로그인
      </Button>
      <div id={panelId} ref={panelRef} hidden={!open}>
        <LoginForm next={next} />
      </div>
    </div>
  );
}
