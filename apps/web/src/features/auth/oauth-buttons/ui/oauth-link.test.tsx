import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiReadinessProvider } from "@/shared/api";

import { OAuthLink } from "./oauth-link";

const HREF = "/api/auth/oauth/google?next=%2Fhome";

function stubHealth(isUp: () => boolean) {
  vi.stubGlobal(
    "fetch",
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify({ status: isUp() ? "UP" : "DOWN" }), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    ),
  );
}

function renderLink(navigate: (href: string) => void) {
  render(
    <ApiReadinessProvider pollIntervalMs={10}>
      <OAuthLink
        href={HREF}
        waitingLabel="서버가 깨면 이동할게요"
        navigate={navigate}
      >
        구글로 계속하기
      </OAuthLink>
    </ApiReadinessProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("OAuthLink 서버 깨우기", () => {
  it("US2-AC8 깨우는 중에 누르면 기다린다는 문구를 보이고, 깨어난 뒤에 한 번만 이동한다", async () => {
    let up = false;
    stubHealth(() => up);
    const navigate = vi.fn();
    renderLink(navigate);
    const user = userEvent.setup();

    // 처음 물어본 답이 DOWN이라 깨우는 중이 된 뒤에 누른다.
    await waitFor(() =>
      expect(vi.mocked(fetch).mock.calls.length).toBeGreaterThanOrEqual(2),
    );
    await user.click(screen.getByRole("link", { name: "구글로 계속하기" }));
    expect(
      await screen.findByRole("link", { name: "서버가 깨면 이동할게요" }),
    ).toHaveAttribute("aria-disabled", "true");
    await user.click(
      screen.getByRole("link", { name: "서버가 깨면 이동할게요" }),
    );
    expect(navigate).not.toHaveBeenCalled();

    up = true;
    await waitFor(() => expect(navigate).toHaveBeenCalledTimes(1));
    expect(navigate).toHaveBeenCalledWith(HREF);
  });

  it("US2-AC8 깨어 있으면 문구를 바꾸지 않고 바로 이동한다", async () => {
    stubHealth(() => true);
    const navigate = vi.fn();
    renderLink(navigate);
    const user = userEvent.setup();

    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    await new Promise((resolve) => setTimeout(resolve, 0));
    await user.click(screen.getByRole("link", { name: "구글로 계속하기" }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(HREF));
  });

  it("공급자 밖에서는 지금처럼 바로 이동한다", async () => {
    const navigate = vi.fn();
    render(
      <OAuthLink href={HREF} waitingLabel="기다려요" navigate={navigate}>
        구글로 계속하기
      </OAuthLink>,
    );
    await userEvent
      .setup()
      .click(screen.getByRole("link", { name: "구글로 계속하기" }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(HREF));
  });
});
