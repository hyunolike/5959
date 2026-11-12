"use client";

import {
  useState,
  type ComponentPropsWithoutRef,
  type MouseEvent,
  type ReactNode,
} from "react";

import { useApiReadiness } from "@/shared/api";

interface OAuthLinkProps extends ComponentPropsWithoutRef<"a"> {
  href: string;
  children: ReactNode;
  /** 서버가 깨기를 기다리는 동안 버튼에 보일 문구. */
  waitingLabel: string;
  /** 테스트에서 실제 이동 대신 쓴다. */
  navigate?: (href: string) => void;
}

/**
 * OAuth 시작 링크. 서버가 잠들어 있으면 제공자로 바로 보내지 않고 깨어난 뒤에 보낸다(018 US2-AC8).
 * 제공자에서 돌아온 콜백은 API를 불러야 하는데, 잠든 채로 보내면 돌아왔을 때 로그인이 실패한다.
 * 자바스크립트가 없으면 일반 링크로 동작한다. 새 탭으로 여는 클릭은 막지 않는다.
 */
export function OAuthLink({
  href,
  children,
  waitingLabel,
  navigate = (to) => window.location.assign(to),
  onClick,
  ...rest
}: OAuthLinkProps) {
  const { status, waitUntilReady } = useApiReadiness();
  const [waiting, setWaiting] = useState(false);

  async function handleClick(event: MouseEvent<HTMLAnchorElement>) {
    onClick?.(event);
    if (event.defaultPrevented) return;
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0)
      return;

    // 깨어 있다고 본 지 오래됐으면 그사이 잠들었을 수 있다. 늘 확인을 거친다. 막 확인했으면 바로 넘어간다.
    event.preventDefault();
    if (waiting) return;
    if (status !== "ready") setWaiting(true);
    const ready = await waitUntilReady();
    setWaiting(false);
    if (ready) navigate(href);
  }

  return (
    <a
      href={href}
      onClick={handleClick}
      aria-disabled={waiting || undefined}
      {...rest}
    >
      {waiting ? waitingLabel : children}
    </a>
  );
}
