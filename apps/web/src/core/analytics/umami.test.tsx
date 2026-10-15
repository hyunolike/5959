import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("next/script", () => ({
  default: (props: Record<string, unknown>) => (
    <script
      data-testid={String(props.id)}
      {...Object.fromEntries(
        Object.entries(props).filter(([key]) => key.startsWith("data-")),
      )}
      src={props.src as string | undefined}
      async
    />
  ),
}));

import { UmamiAnalytics } from "./umami";

describe("UmamiAnalytics", () => {
  it("ANALYTICS-AC3 웹사이트 ID가 없으면 아무 스크립트도 그리지 않는다", () => {
    const { container } = render(<UmamiAnalytics websiteId={null} />);
    expect(container.querySelector("script")).toBeNull();
  });

  it("ANALYTICS-AC3 운영 주소에서만 세고 보내기 전에 주소를 정리하게 한다", () => {
    render(<UmamiAnalytics websiteId="site-id" />);
    // React는 async 스크립트를 head로 올린다. 문서 전체에서 찾는다.
    const tracker = document.querySelector('script[data-testid="umami"]');
    expect(tracker).toHaveAttribute("src", "https://cloud.umami.is/script.js");
    expect(tracker).toHaveAttribute("data-website-id", "site-id");
    expect(tracker).toHaveAttribute("data-domains", "new.5959.site");
    expect(tracker).toHaveAttribute("data-before-send", "__oguUmamiBeforeSend");
    expect(document.getElementById("umami-before-send")?.innerHTML).toContain(
      "__oguUmamiBeforeSend",
    );
  });
});
