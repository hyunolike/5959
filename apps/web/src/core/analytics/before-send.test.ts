import { describe, expect, it } from "vitest";

import {
  BEFORE_SEND_NAME,
  BEFORE_SEND_SCRIPT,
  umamiBeforeSend,
} from "./before-send";

describe("Umami로 보내기 전 주소 정리", () => {
  it("ANALYTICS-AC2 비밀번호 재설정 토큰 같은 검색어는 보내지 않는다", () => {
    const out = umamiBeforeSend("event", {
      url: "/reset-password?token=secret-token",
      referrer: "https://new.5959.site/reset-password?token=secret-token#x",
      website: "w",
    });
    expect(out).toEqual({
      url: "/reset-password",
      referrer: "https://new.5959.site/reset-password",
      website: "w",
    });
    expect(JSON.stringify(out)).not.toContain("secret-token");
  });

  it("ANALYTICS-AC1 utm 꼬리표는 남겨 어느 채널에서 왔는지 셀 수 있다", () => {
    const out = umamiBeforeSend("event", {
      url: "/?utm_source=linkedin&next=/my&utm_campaign=launch",
      referrer: "https://www.linkedin.com/",
    });
    expect(out.url).toBe("/?utm_source=linkedin&utm_campaign=launch");
    expect(out.referrer).toBe("https://www.linkedin.com/");
  });

  it("ANALYTICS-AC2 빈 이전 주소와 문자열이 아닌 값은 그대로 둔다", () => {
    expect(umamiBeforeSend("event", { url: "/home", referrer: "" })).toEqual({
      url: "/home",
      referrer: "",
    });
  });

  it("ANALYTICS-AC2 페이지에 심는 스크립트만으로도 같은 일을 한다", () => {
    const win: Record<string, unknown> = {};
    new Function("window", BEFORE_SEND_SCRIPT)(win);
    const handler = win[BEFORE_SEND_NAME] as typeof umamiBeforeSend;
    expect(handler("event", { url: "/reset-password?token=t" }).url).toBe(
      "/reset-password",
    );
  });
});
