"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  classifyInstallEnvironment,
  INSTALL_DISMISS_KEY,
  INSTALL_DISMISS_MS,
  promotionAllowed,
  type InstallEnvironment,
} from "./platform";

interface InstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed"; platform: string }>;
}

interface InstallState {
  environment: InstallEnvironment | null;
  installed: boolean;
  canPrompt: boolean;
  pending: boolean;
  promotionVisible: boolean;
  message: string;
  error: boolean;
  dismissPromotion: () => void;
  requestInstall: () => Promise<void>;
}

const InstallContext = createContext<InstallState | null>(null);

function readDismissal() {
  try {
    const value = Number(localStorage.getItem(INSTALL_DISMISS_KEY));
    return Number.isFinite(value) &&
      value > 0 &&
      value <= Date.now() + INSTALL_DISMISS_MS
      ? value
      : 0;
  } catch {
    return 0;
  }
}

export function InstallAppProvider({ children }: { children: ReactNode }) {
  const [environment, setEnvironment] = useState<InstallEnvironment | null>(
    null,
  );
  const [dismissedUntil, setDismissedUntil] = useState(0);
  const [now, setNow] = useState(0);
  const [promptAvailable, setPromptAvailable] = useState(false);
  const [confirmedInstalled, setConfirmedInstalled] = useState(false);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState(false);
  const promptRef = useRef<InstallPromptEvent | null>(null);
  const environmentRef = useRef<InstallEnvironment | null>(null);
  const requestingRef = useRef(false);
  const installedRef = useRef(false);
  const mountedRef = useRef(false);

  const dismissPromotion = useCallback(() => {
    const currentTime = Date.now();
    const until = currentTime + INSTALL_DISMISS_MS;
    setNow(currentTime);
    setDismissedUntil(until);
    try {
      localStorage.setItem(INSTALL_DISMISS_KEY, String(until));
    } catch {
      /* 안내는 이 화면에서도 숨긴다. */
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    const display = window.matchMedia("(display-mode: standalone)");
    const update = () => {
      setNow(Date.now());
      const standalone =
        display.matches ||
        (navigator as Navigator & { standalone?: boolean }).standalone === true;
      const next = classifyInstallEnvironment({
        userAgent: navigator.userAgent,
        platform: navigator.platform,
        maxTouchPoints: navigator.maxTouchPoints ?? 0,
        secure: window.isSecureContext,
        standalone,
      });
      environmentRef.current = next;
      setEnvironment(next);
      setDismissedUntil((current) => Math.max(current, readDismissal()));
      if (standalone) {
        promptRef.current = null;
        setPromptAvailable(false);
      }
    };
    const offer = (event: Event) => {
      const current = environmentRef.current;
      const candidate = event as Partial<InstallPromptEvent>;
      if (
        !current?.secure ||
        current.standalone ||
        current.inApp ||
        installedRef.current ||
        requestingRef.current ||
        typeof candidate.prompt !== "function" ||
        typeof candidate.userChoice?.then !== "function"
      )
        return;
      event.preventDefault();
      promptRef.current = candidate as InstallPromptEvent;
      setPromptAvailable(true);
    };
    const installed = () => {
      installedRef.current = true;
      promptRef.current = null;
      setPromptAvailable(false);
      setConfirmedInstalled(true);
      setMessage("");
      setError(false);
    };
    update();
    window.addEventListener("beforeinstallprompt", offer);
    window.addEventListener("appinstalled", installed);
    window.addEventListener("pageshow", update);
    window.addEventListener("storage", update);
    display.addEventListener("change", update);
    return () => {
      mountedRef.current = false;
      window.removeEventListener("beforeinstallprompt", offer);
      window.removeEventListener("appinstalled", installed);
      window.removeEventListener("pageshow", update);
      window.removeEventListener("storage", update);
      display.removeEventListener("change", update);
    };
  }, []);

  useEffect(() => {
    const remaining = dismissedUntil - Date.now();
    if (remaining <= 0) return;
    const timer = window.setTimeout(() => {
      setNow(Date.now());
      setDismissedUntil(0);
    }, remaining);
    return () => window.clearTimeout(timer);
  }, [dismissedUntil]);

  const requestInstall = useCallback(async () => {
    const current = environmentRef.current;
    const event = promptRef.current;
    if (
      requestingRef.current ||
      installedRef.current ||
      !event ||
      !current?.secure ||
      current.standalone ||
      current.inApp
    )
      return;
    requestingRef.current = true;
    promptRef.current = null;
    setPromptAvailable(false);
    setPending(true);
    setMessage("");
    setError(false);
    try {
      await event.prompt();
      const choice = await event.userChoice;
      if (!mountedRef.current) return;
      dismissPromotion();
      if (!installedRef.current)
        setMessage(
          choice.outcome === "accepted"
            ? "브라우저 안내를 확인해주세요. 설치 완료 여부는 브라우저가 알려줍니다."
            : "설치를 취소했어요. 홈 화면 추가 방법은 언제든 다시 볼 수 있어요.",
        );
    } catch {
      if (mountedRef.current) {
        setError(true);
        setMessage(
          "설치 안내를 열지 못했어요. 홈 화면 추가 방법을 확인해주세요.",
        );
      }
    } finally {
      requestingRef.current = false;
      if (mountedRef.current) setPending(false);
    }
  }, [dismissPromotion]);

  const installed = confirmedInstalled || !!environment?.standalone;
  const canPrompt =
    !!environment?.secure &&
    !environment.inApp &&
    !installed &&
    promptAvailable;
  return (
    <InstallContext.Provider
      value={{
        environment,
        installed,
        canPrompt,
        pending,
        promotionVisible:
          !!environment &&
          !installed &&
          promotionAllowed(
            environment,
            promptAvailable || pending,
            dismissedUntil,
            now,
          ),
        message,
        error,
        dismissPromotion,
        requestInstall,
      }}
    >
      {children}
    </InstallContext.Provider>
  );
}

export function useInstallApp() {
  const value = useContext(InstallContext);
  if (!value) throw new Error("InstallAppProvider is required");
  return value;
}
