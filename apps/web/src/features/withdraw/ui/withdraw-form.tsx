"use client";

import Link from "next/link";
import { useId, useState } from "react";

import { ApiError, pauseUnauthorizedRedirect } from "@/shared/api";
import { Button } from "@/shared/ui";

import { useWithdrawMutation } from "../api/use-withdraw-mutation";

/** 탈퇴하면 사라지는 것(013 FR-002). */
const REMOVED = [
  "내가 쓴 글과 댓글",
  "받은 알림과 주간 리포트",
  "몬스터 도감과 감정 기록",
  "닉네임, 직군, 경력과 로그인 정보",
];

/** 탈퇴해도 남는 것. 다른 회원이 이미 받은 것은 숫자로 남는다(FR-009). */
const KEPT = [
  "다른 회원의 글에 남긴 공감 수",
  "함께 줄인 몬스터의 HP와 레이드 기록",
];

const FAILED_MESSAGE = "탈퇴하지 못했어요. 잠시 후 다시 시도해 주세요.";
const OPERATOR_MESSAGE =
  "운영자로 지정된 계정이에요. 운영자 지정을 먼저 푼 뒤에 탈퇴할 수 있어요.";

function messageOf(error: unknown): string {
  if (error instanceof ApiError && error.code === "OPERATOR_CANNOT_WITHDRAW") {
    return OPERATOR_MESSAGE;
  }
  return FAILED_MESSAGE;
}

interface WithdrawFormProps {
  /** 취소하면 돌아갈 곳. 프로필 수정에서 왔으면 그곳, 온보딩에서 왔으면 온보딩이다. */
  cancelHref: string;
  /**
   * 탈퇴에 성공했을 때(쿠키가 지워진 뒤) 한 번 부른다. 화면이 이 세션에 묶인 것(실시간 알림
   * 연결 등)을 닫는 데 쓴다.
   */
  onWithdrawn?: () => void;
  /** 화면을 떠나는 방법. 기본은 새로 받는 이동이다. 테스트가 바꿔 끼운다. */
  navigate?: (to: string) => void;
}

/**
 * 주소를 바꿔 끼우며 새로 받는다. 세션이 끝났으므로 쿼리 캐시와 레이아웃(알림 종)을 통째로 버리는
 * 편이 맞고, 뒤로 가기로 탈퇴 화면에 돌아오지 않는다.
 */
function replaceLocation(to: string): void {
  window.location.replace(to);
}

/**
 * 회원 탈퇴 확인(013 US1). 사라지는 것과 남는 것, 되돌릴 수 없다는 것을 먼저 알리고, 확인 칸을
 * 채운 뒤에만 탈퇴할 수 있다(FR-002, FR-003).
 *
 * 탈퇴를 말리지 않는다(FR-020). 취소와 탈퇴를 같은 줄에 같은 크기로 두고, 되묻는 창을 띄우지 않는다.
 * 확인 칸을 채우기 전의 탈퇴 버튼은 `disabled`가 아니라 `aria-disabled`다. 초점을 받을 수 있어야
 * 화면 낭독기로 "왜 눌리지 않는지"를 들을 수 있다(FR-021).
 *
 * 성공하면 로그인 화면을 새로 받는다. 세션이 이미 끝나 401이 오면 BFF가 쿠키를 지웠으므로
 * 역시 로그인 화면으로 보낸다. 그 밖의 실패는 안내만 보이고 계정과 로그인 상태는 그대로다(US1-AC8).
 *
 * 요청을 보내기 전에 "조회의 401은 세션 만료"라는 처리를 멈춘다. 탈퇴가 받아들여지면 화면에 떠 있던
 * 조회(내 정보, 안 읽은 알림 수)가 401을 받는데, 그것이 먼저 `/login?next=/withdraw`로 보내 버리면
 * 탈퇴가 끝났다는 안내가 보이지 않는다. 실패했을 때만 그 처리를 되돌린다. 성공하면 화면을 새로 받으므로
 * 되돌릴 것이 없다.
 */
export function WithdrawForm({
  cancelHref,
  onWithdrawn,
  navigate = replaceLocation,
}: WithdrawFormProps) {
  const withdrawMutation = useWithdrawMutation();
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const confirmId = useId();
  const hintId = useId();

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!confirmed || withdrawMutation.isPending) {
      return;
    }
    setError(null);
    const resumeRedirect = pauseUnauthorizedRedirect();
    try {
      await withdrawMutation.mutateAsync();
    } catch (caught) {
      resumeRedirect();
      if (caught instanceof ApiError && caught.status === 401) {
        navigate("/login");
        return;
      }
      setError(messageOf(caught));
      return;
    }
    onWithdrawn?.();
    navigate("/login?withdrawn=1");
  };

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
      <section aria-labelledby={`${confirmId}-removed`}>
        <h2 id={`${confirmId}-removed`} className="text-body text-fg font-bold">
          탈퇴하면 사라져요
        </h2>
        <ul className="text-body-sm text-fg-muted mt-2 flex list-disc flex-col gap-1 pl-5">
          {REMOVED.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      </section>

      <section aria-labelledby={`${confirmId}-kept`}>
        <h2 id={`${confirmId}-kept`} className="text-body text-fg font-bold">
          탈퇴해도 남아요
        </h2>
        <ul className="text-body-sm text-fg-muted mt-2 flex list-disc flex-col gap-1 pl-5">
          {KEPT.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
        <p className="text-body-sm text-fg-subtle mt-2 leading-6 break-keep">
          숫자만 남고, 누가 남겼는지는 알 수 없게 돼요.
        </p>
      </section>

      <p className="text-body-sm text-fg leading-6 break-keep">
        탈퇴는 바로 끝나고 <strong>되돌릴 수 없어요.</strong> 같은 계정으로 다시
        가입할 수 있지만 예전 글과 기록은 돌아오지 않아요.
      </p>

      <div className="flex flex-col gap-2">
        <label
          htmlFor={confirmId}
          className="text-body-sm text-fg flex min-h-11 cursor-pointer items-center gap-3"
        >
          <input
            id={confirmId}
            type="checkbox"
            checked={confirmed}
            onChange={(event) => setConfirmed(event.target.checked)}
            className="accent-action size-5 shrink-0"
          />
          <span className="break-keep">
            안내를 모두 읽었고, 되돌릴 수 없다는 것을 알고 있어요.
          </span>
        </label>
        <p id={hintId} className="text-label text-fg-subtle break-keep">
          확인 칸을 채우면 탈퇴할 수 있어요.
        </p>
      </div>

      {error ? (
        <p role="alert" className="text-body-sm text-danger break-keep">
          {error}
        </p>
      ) : null}

      <div className="grid grid-cols-2 gap-3">
        <Link
          href={cancelHref}
          className="ogu-pressable bg-surface-sunken text-fg hover:bg-line rounded-control text-body-sm inline-flex min-h-12 items-center justify-center px-5 py-2 text-center font-bold"
        >
          취소
        </Link>
        <Button
          type="submit"
          variant="danger"
          aria-disabled={!confirmed || withdrawMutation.isPending}
          aria-describedby={confirmed ? undefined : hintId}
        >
          {withdrawMutation.isPending ? "탈퇴하는 중" : "탈퇴하기"}
        </Button>
      </div>
    </form>
  );
}
