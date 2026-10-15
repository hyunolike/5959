import { render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { loaderScript, UmamiAnalytics } from "./umami";

const PROD = "https://new.5959.site";

function runLoader(storage: { getItem: () => string | null }) {
  const head = { appended: [] as HTMLScriptElement[] };
  const win: Record<string, unknown> = { localStorage: storage };
  const doc = {
    createElement: () => ({ dataset: {} }) as unknown as HTMLScriptElement,
    head: { appendChild: (s: HTMLScriptElement) => head.appended.push(s) },
  };
  new Function("window", "document", loaderScript("site-id"))(win, doc);
  return { win, appended: head.appended };
}

afterEach(() => {
  document.body.innerHTML = "";
});

describe("UmamiAnalytics", () => {
  it("ANALYTICS-AC3 웹사이트 ID가 없으면 아무 스크립트도 그리지 않는다", () => {
    const { container } = render(
      <UmamiAnalytics appOrigin={PROD} websiteId={null} />,
    );
    expect(container.querySelector("script")).toBeNull();
  });

  it("ANALYTICS-AC3 운영 주소가 아니면(로컬, e2e, 미리보기) 그리지 않는다", () => {
    for (const origin of ["http://localhost:3000", "https://x.run.app"]) {
      const { container } = render(
        <UmamiAnalytics appOrigin={origin} websiteId="site-id" />,
      );
      expect(container.querySelector("script")).toBeNull();
    }
  });

  it("ANALYTICS-AC3 운영 주소에서는 트래커를 붙이는 스크립트를 그린다", () => {
    const { container } = render(
      <UmamiAnalytics appOrigin={PROD} websiteId="site-id" />,
    );
    expect(container.querySelector("#umami-loader")?.innerHTML).toContain(
      "__oguUmamiBeforeSend",
    );
  });

  it("ANALYTICS-AC3 트래커는 운영 주소에서만 세고 보내기 전에 주소를 정리하게 붙는다", () => {
    const { win, appended } = runLoader({ getItem: () => null });
    expect(appended).toHaveLength(1);
    expect(appended[0].src).toBe("https://cloud.umami.is/script.js");
    expect({ ...appended[0].dataset }).toEqual({
      websiteId: "site-id",
      domains: "new.5959.site",
      beforeSend: "__oguUmamiBeforeSend",
    });
    expect(typeof win.__oguUmamiBeforeSend).toBe("function");
  });

  it("ANALYTICS-AC4 저장소가 막힌 브라우저에서는 트래커를 붙이지 않고 오류도 내지 않는다", () => {
    const { appended } = runLoader({
      getItem: () => {
        throw new DOMException("blocked", "SecurityError");
      },
    });
    expect(appended).toHaveLength(0);
  });
});
