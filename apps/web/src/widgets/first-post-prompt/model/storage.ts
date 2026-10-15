/**
 * 첫 고민 안내가 브라우저에 두는 두 값(016 data-model). 저장소가 막힌 브라우저(사생활 보호 창 등)에서는
 * 읽기와 쓰기가 던질 수 있다. 모든 접근을 감싸고, 실패하면 "없음"으로 본다.
 */
const WELCOME_PENDING_KEY = "ogu.welcome.pending";
const DISMISSED_KEY = "ogu.firstPostPrompt.dismissed";

/** 온보딩 직후인지 읽고 바로 지운다. 새로 고치거나 다시 열면 `false`다(US2-AC5). */
export function consumeWelcomePending(): boolean {
  try {
    const pending = sessionStorage.getItem(WELCOME_PENDING_KEY) === "1";
    sessionStorage.removeItem(WELCOME_PENDING_KEY);
    return pending;
  } catch {
    return false;
  }
}

/**
 * 이번 화면이 읽은 환영 표시. 저장소의 값은 읽자마자 지우므로, StrictMode가 상태 초기화를 두 번
 * 불러도 같은 답을 내도록 화면이 떠 있는 동안 기억한다. 화면을 떠나면 `forgetWelcome`으로 비운다.
 */
let welcomeForThisView: boolean | undefined;

export function takeWelcome(): boolean {
  welcomeForThisView ??= consumeWelcomePending();
  return welcomeForThisView;
}

export function forgetWelcome(): void {
  welcomeForThisView = undefined;
}

export function isPromptDismissed(): boolean {
  try {
    return localStorage.getItem(DISMISSED_KEY) === "1";
  } catch {
    return false;
  }
}

/** 첫 고민 카드를 닫았다고 남긴다. 남기지 못하면 이번 화면에서만 닫힌다. */
export function dismissPrompt(): void {
  try {
    localStorage.setItem(DISMISSED_KEY, "1");
  } catch {
    // 이번 화면의 상태로만 닫는다.
  }
}
