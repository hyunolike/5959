export interface InstallEnvironment {
  platform: "ios" | "android" | "desktop";
  inApp: boolean;
  secure: boolean;
  standalone: boolean;
}

export function classifyInstallEnvironment(input: {
  userAgent: string;
  platform: string;
  maxTouchPoints: number;
  secure: boolean;
  standalone: boolean;
}): InstallEnvironment {
  const ios =
    /iPhone|iPad|iPod/i.test(input.userAgent) ||
    (input.platform === "MacIntel" && input.maxTouchPoints > 1);
  return {
    platform: ios
      ? "ios"
      : /Android/i.test(input.userAgent)
        ? "android"
        : "desktop",
    inApp: /KAKAOTALK|NAVER|Instagram|FBAN|FBAV|Line\/|DaumApps|; wv\)/i.test(
      input.userAgent,
    ),
    secure: input.secure,
    standalone: input.standalone,
  };
}

export function promotionAllowed(
  environment: InstallEnvironment,
  promptAvailable: boolean,
  dismissedUntil: number,
  now: number,
) {
  return (
    environment.secure &&
    !environment.standalone &&
    !environment.inApp &&
    dismissedUntil <= now &&
    (promptAvailable || environment.platform === "ios")
  );
}

export const INSTALL_DISMISS_KEY = "ogu-install-dismissed-until:v1";
export const INSTALL_DISMISS_MS = 7 * 24 * 60 * 60 * 1000;
