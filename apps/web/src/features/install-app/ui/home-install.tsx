"use client";

import Image from "next/image";
import { Download, X } from "lucide-react";
import { useRef, useState } from "react";
import { Button, ConfirmDialog } from "@/shared/ui";
import { useInstallApp } from "../model/install-provider";

/** 설치 권한을 자동으로 묻지 않는다. 실제 prompt 이벤트가 있으면 사용자 클릭으로만 연다. */
export function HomeInstall() {
  const state = useInstallApp();
  const [guideOpen, setGuideOpen] = useState(false);
  const guideButton = useRef<HTMLButtonElement>(null);
  if (!state.environment || state.installed) return null;
  const { platform, inApp, secure } = state.environment;
  const guide = (
    <button
      ref={guideButton}
      type="button"
      aria-label="홈 화면 추가 방법"
      onClick={() => setGuideOpen(true)}
      className="focus-visible:ring-brand-600 inline-flex min-h-11 min-w-11 items-center justify-center self-start rounded-sm text-xs text-neutral-500 underline underline-offset-4 focus-visible:ring-2"
    >
      {state.promotionVisible ? "방법" : "홈 화면 추가 방법"}
    </button>
  );
  return (
    <aside aria-label="홈 화면 추가" className="flex flex-col gap-2">
      {state.promotionVisible ? (
        <div className="flex items-start gap-2 border-y border-neutral-200 py-3 sm:items-center">
          <Image
            src="/brand/ogu-companion.png"
            alt=""
            width={28}
            height={23}
            className="mt-1 shrink-0 object-contain"
          />
          <div className="min-w-0 flex-1 sm:flex sm:items-center sm:justify-between sm:gap-3">
            <div>
              <p className="text-sm font-bold text-neutral-900">
                오구오구를 홈 화면에서 만나세요
              </p>
              <p className="mt-1 text-xs leading-relaxed text-neutral-500">
                앱처럼 바로 열고 이야기를 이어갈 수 있어요.
              </p>
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-2 sm:mt-0">
              {state.canPrompt || state.pending ? (
                <Button
                  variant="secondary"
                  size="sm"
                  className="min-h-11 px-2"
                  disabled={state.pending}
                  aria-busy={state.pending}
                  onClick={() =>
                    state.canPrompt
                      ? void state.requestInstall()
                      : setGuideOpen(true)
                  }
                >
                  <Download size={15} aria-hidden />
                  {state.pending
                    ? "브라우저 안내 여는 중"
                    : state.canPrompt
                      ? "홈 화면에 추가"
                      : "추가 방법 보기"}
                </Button>
              ) : null}
              {guide}
            </div>
          </div>
          <button
            type="button"
            aria-label="설치 안내 7일간 숨기기"
            className="focus-visible:ring-brand-600 inline-flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded-lg text-neutral-500 hover:bg-neutral-100 focus-visible:ring-2"
            onClick={state.dismissPromotion}
          >
            <X size={18} aria-hidden />
          </button>
        </div>
      ) : null}
      {!state.promotionVisible ? guide : null}
      <p
        role={state.error ? "alert" : "status"}
        aria-live="polite"
        className={
          !state.message
            ? "sr-only"
            : state.error
              ? "text-xs text-red-600"
              : "text-xs text-neutral-600"
        }
      >
        {state.message}
      </p>
      <ConfirmDialog
        open={guideOpen}
        title="홈 화면에 오구오구 추가하기"
        description="브라우저에 따라 메뉴 이름과 설치 지원 여부가 달라요."
        confirmLabel="알겠어요"
        cancelLabel="닫기"
        confirmVariant="primary"
        role="dialog"
        presentation="sheet"
        dismissOnBack
        finalFocus={() =>
          guideButton.current ?? document.querySelector<HTMLElement>("main")
        }
        onConfirm={() => setGuideOpen(false)}
        onCancel={() => setGuideOpen(false)}
      >
        <div className="text-sm leading-relaxed text-neutral-700">
          {!secure ? (
            <p>
              보안 연결(HTTPS)에서 다시 열어주세요. 브라우저가 현재 주소에서
              설치를 허용하지 않을 수 있어요.
            </p>
          ) : inApp ? (
            <p>
              현재 앱의 메뉴에서 ‘기본 브라우저로 열기’ 또는 ‘Safari/Chrome으로
              열기’를 선택한 뒤, 이 페이지의 추가 방법을 다시 확인해주세요.
            </p>
          ) : platform === "ios" ? (
            <ol className="list-decimal space-y-3 pl-5">
              <li>Safari에서 이 페이지를 열고 공유 메뉴를 누르세요.</li>
              <li>
                ‘홈 화면에 추가’를 선택하세요. 메뉴가 없으면 공유 메뉴의 작업
                목록을 확인해주세요.
              </li>
              <li>‘웹 앱으로 열기’ 옵션이 보이면 켜고 ‘추가’를 누르세요.</li>
            </ol>
          ) : (
            <p>
              브라우저 메뉴에서 ‘앱 설치’ 또는 ‘홈 화면에 추가’를 찾아보세요.
              메뉴가 없으면 설치를 지원하는 브라우저에서 다시 열어주세요.
            </p>
          )}
          {(state.canPrompt || state.pending) && !inApp && secure ? (
            <Button
              className="mt-4 w-full"
              disabled={state.pending}
              aria-busy={state.pending}
              onClick={() => void state.requestInstall()}
            >
              {state.pending
                ? "브라우저 안내 여는 중"
                : "브라우저 설치 안내 열기"}
            </Button>
          ) : null}
          <p
            role={state.error ? "alert" : "status"}
            className={
              state.error
                ? "mt-3 text-xs text-red-600"
                : "mt-3 text-xs text-neutral-600"
            }
          >
            {state.message}
          </p>
        </div>
      </ConfirmDialog>
    </aside>
  );
}
