import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { RaidState } from "@/entities/raid";
import { QUERY_KEYS } from "@/shared/config";

import { AttackButton } from "./attack-button";

const RAID: RaidState = {
  boss: {
    bossId: 7,
    emotion: "ANXIETY",
    maxHp: 300,
    hp: 200,
    status: "ALIVE",
    participantCount: 5,
    spawnedAt: "2026-10-09T00:00:00Z",
    endedAt: null,
  },
  myDamage: 3,
  nextBossAt: null,
  available: true,
  epoch: 10,
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function accepted(hp = 199, cooldownMs = 1000) {
  return json({
    success: true,
    data: {
      bossId: 7,
      hp,
      maxHp: 300,
      status: "ALIVE",
      participantCount: 5,
      myDamage: 4,
      defeated: false,
      cooldownMs,
    },
    error: null,
  });
}

function rejected(status: number, code: string) {
  return json(
    { success: false, data: null, error: { code, message: "서버 문구" } },
    status,
  );
}

function renderButton(disabled = false) {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  queryClient.setQueryData(QUERY_KEYS.raid, RAID);
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  render(
    <QueryClientProvider client={queryClient}>
      <AttackButton bossId={7} disabled={disabled} />
    </QueryClientProvider>,
  );
  const raid = () => queryClient.getQueryData<RaidState>(QUERY_KEYS.raid);
  return { raid, invalidate };
}

const button = () => screen.getByRole("button", { name: "공격하기" });

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("AttackButton", () => {
  it("US1-AC2 누르면 공격을 보내고 HP와 내 기여를 응답으로 바꾼다", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue(accepted());
    vi.stubGlobal("fetch", fetchMock);
    const { raid } = renderButton();

    await user.click(button());

    await waitFor(() => expect(raid()?.boss?.hp).toBe(199));
    expect(raid()?.myDamage).toBe(4);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/raid/attacks");
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      bossId: 7,
    });
  });

  it("US1-AC3 쿨다운 동안 버튼이 눌리지 않고 지나면 다시 눌린다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const fetchMock = vi.fn().mockImplementation(async () => accepted());
    vi.stubGlobal("fetch", fetchMock);
    renderButton();

    await user.click(button());
    await waitFor(() =>
      expect(button()).toHaveAttribute("aria-disabled", "true"),
    );
    await user.click(button());
    await user.click(button());
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await act(() => vi.advanceTimersByTimeAsync(1000));
    expect(button()).toHaveAttribute("aria-disabled", "false");
    await user.click(button());
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  });

  it("US1-AC3 서버가 쿨다운이라고 답하면 HP를 바꾸지 않고 잠시 잠근다", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(rejected(429, "RAID_COOLDOWN")),
    );
    const { raid } = renderButton();

    await user.click(button());

    await waitFor(() =>
      expect(button()).toHaveAttribute("aria-disabled", "true"),
    );
    expect(raid()?.boss?.hp).toBe(200);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("US1-AC6 보스가 끝났다고 답하면 조회를 다시 한다", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(rejected(409, "RAID_BOSS_ENDED")),
    );
    const { invalidate } = renderButton();

    await user.click(button());

    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({ queryKey: QUERY_KEYS.raid }),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("US3-AC6 레이드가 쉬는 중이라고 답하면 화면의 상태에 적고 HP는 그대로 둔다", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(rejected(503, "RAID_UNAVAILABLE")),
    );
    const { raid } = renderButton();

    await user.click(button());

    await waitFor(() => expect(raid()?.available).toBe(false));
    expect(raid()?.boss?.hp).toBe(200);
  });

  it("쉬는 중이면 눌러도 보내지 않는다", async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    renderButton(true);

    await user.click(button());

    expect(button()).toHaveAttribute("aria-disabled", "true");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("그 밖의 실패는 다시 시도하라고 알린다", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(rejected(500, "INTERNAL_ERROR")),
    );
    renderButton();

    await user.click(button());

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "공격하지 못했어요. 잠시 후 다시 시도해 주세요.",
    );
  });
});
