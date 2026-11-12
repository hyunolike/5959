import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiReadinessProvider } from "@/shared/api";

import { ApiWakeNotice } from "./api-wake-notice";

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

function renderNotice(giveUpAfterMs = 60_000) {
  render(
    <ApiReadinessProvider pollIntervalMs={10} giveUpAfterMs={giveUpAfterMs}>
      <ApiWakeNotice />
    </ApiReadinessProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("ApiWakeNotice", () => {
  it("US2-AC7 잠들어 있으면 깨우는 중이라는 안내를 보이고, 깨어나면 사라진다", async () => {
    let up = false;
    stubHealth(() => up);
    renderNotice();

    expect(await screen.findByText("서버를 깨우고 있어요")).toBeInTheDocument();
    expect(screen.getByText(/4~5분쯤 걸려요/)).toBeInTheDocument();

    up = true;
    await vi.waitFor(() =>
      expect(
        screen.queryByText("서버를 깨우고 있어요"),
      ).not.toBeInTheDocument(),
    );
  });

  it("US2-AC7 깨어 있으면 아무 안내도 보이지 않는다", async () => {
    stubHealth(() => true);
    renderNotice();
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("US2-AC8 끝내 깨어나지 않으면 잠시 뒤 다시 하라고 안내한다", async () => {
    stubHealth(() => false);
    renderNotice(30);
    expect(
      await screen.findByText("서버가 아직 일어나지 않았어요"),
    ).toBeInTheDocument();
  });
});
