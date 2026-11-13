"use client";

import { useQueryClient } from "@tanstack/react-query";
import { usePathname } from "next/navigation";
import { Activity, useEffect, useRef, useState, type ReactNode } from "react";

import { ApiReadinessProvider, useApiReadiness } from "@/shared/api";
import { BrandMark, Button, Spinner } from "@/shared/ui";
import { ApiWakeNotice } from "@/widgets/auth-welcome";

/** 세션 쿠키로 바로 진입한 회원도 서버 준비를 확인한 뒤 기능 조회를 시작한다. */
export function AuthenticatedApiBoundary({
  authenticated,
  children,
}: {
  authenticated: boolean;
  children: ReactNode;
}) {
  return authenticated ? (
    <ApiReadinessProvider recheckOnResume>
      <ReadySession>{children}</ReadySession>
    </ApiReadinessProvider>
  ) : (
    children
  );
}

function ReadySession({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { status, waitUntilReady } = useApiReadiness();
  const queryClient = useQueryClient();
  const ready = status === "ready";
  const [opened, setOpened] = useState(false);
  const [playing, setPlaying] = useState(false);
  const previouslyReady = useRef(false);
  const previousStatus = useRef(status);
  if (ready && !opened) setOpened(true);

  useEffect(() => {
    if (ready) void waitUntilReady();
  }, [pathname, ready, waitUntilReady]);

  useEffect(() => {
    if (ready) {
      if (previouslyReady.current && previousStatus.current !== "ready") {
        // 서버가 쉬는 사이 실패하거나 낡은 조회를 복구한다. 첫 진입 조회는 중복시키지 않는다.
        void queryClient.invalidateQueries(
          { refetchType: "active" },
          { cancelRefetch: false },
        );
      }
      previouslyReady.current = true;
    }
    previousStatus.current = status;
  }, [queryClient, ready, status]);

  return (
    <>
      {!ready || playing ? (
        <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col items-center justify-center gap-6 px-5 py-10">
          <BrandMark />
          <h1 className="sr-only">서버 연결 확인</h1>
          {status === "checking" ? (
            <Spinner label="서버 연결 확인 중" />
          ) : (
            <ApiWakeNotice context="session" onPlayingChange={setPlaying} />
          )}
          {status === "unavailable" ? (
            <Button onClick={() => void waitUntilReady()}>다시 연결하기</Button>
          ) : null}
        </main>
      ) : null}
      {/* 돌아온 탭의 입력, 스크롤, 세션은 보존한다. 처음 준비되기 전에는 조회와 SSE를 마운트하지 않는다. */}
      {opened ? (
        <Activity mode={ready && !playing ? "visible" : "hidden"}>
          {children}
        </Activity>
      ) : null}
    </>
  );
}
