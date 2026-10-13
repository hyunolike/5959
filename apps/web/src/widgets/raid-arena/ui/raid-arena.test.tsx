import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { RaidState } from "@/entities/raid";

import { BossBanner } from "./boss-banner";
import { elapsedLabel, RAID_PAUSED_NOTICE, RaidArena } from "./raid-arena";

const stream = vi.hoisted(() => ({
  status: "open",
  topics: [] as string[],
}));
const query = vi.hoisted(() => ({
  result: {} as { data?: unknown; isPending: boolean; isError: boolean },
  options: [] as { polling?: boolean }[],
}));

vi.mock("@/features/notification-stream", () => ({
  useNotificationStreamStatus: () => stream.status,
  useStreamTopic: (topic: string) => stream.topics.push(topic),
}));
vi.mock("@/entities/raid", async (original) => ({
  ...(await original<typeof import("@/entities/raid")>()),
  useRaidQuery: (options: { polling?: boolean } = {}) => {
    query.options.push(options);
    return query.result;
  },
}));
vi.mock("@/entities/monster", async (original) => ({
  ...(await original<typeof import("@/entities/monster")>()),
  MonsterDisplay: ({
    monster,
    variant,
  }: {
    monster: { hp: number; maxHp: number; status: string };
    variant: string;
  }) => (
    <div data-testid="boss" data-variant={variant} data-status={monster.status}>
      HP {monster.hp}/{monster.maxHp}
    </div>
  ),
  MonsterSprite: () => <span data-testid="sprite" />,
}));

function raid(overrides: Partial<RaidState> = {}): RaidState {
  return {
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
    ...overrides,
  };
}

function ended(status: "DEFEATED" | "RETREATED"): RaidState {
  const base = raid();
  return {
    ...base,
    boss: {
      ...base.boss!,
      hp: status === "DEFEATED" ? 0 : 120,
      status,
      endedAt: "2026-10-09T02:30:00Z",
    },
    nextBossAt: "2026-10-09T15:00:00Z",
  };
}

function renderWith(ui: React.ReactNode, data?: RaidState) {
  query.result = { data, isPending: false, isError: false };
  render(
    <QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>,
  );
}

beforeEach(() => {
  stream.status = "open";
  stream.topics = [];
  query.options = [];
});

describe("RaidArena", () => {
  it("US1-AC1 보스와 HP, 참여자 수, 내 기여, 공격 버튼을 보인다", () => {
    renderWith(<RaidArena />, raid());

    const boss = screen.getByRole("region", { name: "보스" });
    expect(within(boss).getByTestId("boss")).toHaveTextContent("HP 200/300");
    expect(within(boss).getByTestId("boss")).toHaveAttribute(
      "data-variant",
      "boss",
    );
    expect(boss).toHaveTextContent("함께한 회원5명");
    expect(boss).toHaveTextContent("내 기여3");
    expect(screen.getByRole("button", { name: "공격하기" })).toHaveAttribute(
      "aria-disabled",
      "false",
    );
  });

  it("이 화면이 보이는 동안 레이드 소식을 함께 받는다", () => {
    renderWith(<RaidArena />, raid());

    expect(stream.topics).toContain("raid");
  });

  it("US2-AC6 실시간 연결이 열려 있지 않으면 값을 주기적으로 다시 받는다", () => {
    stream.status = "retrying";
    renderWith(<RaidArena />, raid());
    expect(query.options.at(-1)).toEqual({ polling: true });
  });

  it("실시간 연결이 열려 있으면 다시 받지 않는다", () => {
    renderWith(<RaidArena />, raid());
    expect(query.options.at(-1)).toEqual({ polling: false });
  });

  it("US3-AC6 쉬는 중이면 안내를 보이고 공격 버튼을 막는다", () => {
    renderWith(<RaidArena />, raid({ available: false }));

    expect(screen.getByRole("status")).toHaveTextContent(RAID_PAUSED_NOTICE);
    expect(screen.getByRole("button", { name: "공격하기" })).toHaveAttribute(
      "aria-disabled",
      "true",
    );
  });

  it("US4-AC3 처치되면 함께한 회원 수, 걸린 시간, 내 기여, 다음 보스가 나오는 때를 보인다", () => {
    renderWith(<RaidArena />, ended("DEFEATED"));

    const result = screen.getByRole("region", { name: "레이드 결과" });
    expect(within(result).getByRole("heading")).toHaveTextContent(
      "불안 보스를 함께 물리쳤어요",
    );
    expect(result).toHaveTextContent("함께한 회원5명");
    expect(result).toHaveTextContent("걸린 시간2시간 30분");
    expect(result).toHaveTextContent("내 기여3");
    expect(result).toHaveTextContent("다음 보스는");
    expect(within(result).getByTestId("boss")).toHaveAttribute(
      "data-status",
      "DEFEATED",
    );
    // US2-AC7: 끝난 뒤에는 공격 버튼이 없다
    expect(
      screen.queryByRole("button", { name: "공격하기" }),
    ).not.toBeInTheDocument();
  });

  it("US4-AC4 결과에 다른 회원의 닉네임, 기여, 순위가 없다", () => {
    renderWith(<RaidArena />, ended("DEFEATED"));

    const text = screen.getByRole("region", {
      name: "레이드 결과",
    }).textContent;
    expect(text).not.toMatch(/순위|1위|닉네임|가장 많이/);
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("US5-AC5 물러난 보스는 물러났다고 알리고 공격 버튼이 없다", () => {
    renderWith(<RaidArena />, ended("RETREATED"));

    expect(screen.getByRole("heading")).toHaveTextContent(
      "불안 보스가 물러났어요",
    );
    expect(
      screen.queryByRole("button", { name: "공격하기" }),
    ).not.toBeInTheDocument();
  });

  it("보스가 한 번도 없었으면 곧 나타난다고 알린다", () => {
    renderWith(<RaidArena />, raid({ boss: null }));

    expect(screen.getByText("곧 첫 보스가 나타나요.")).toBeInTheDocument();
  });

  it("불러오지 못하면 안내를 보인다", () => {
    query.result = { isPending: false, isError: true };
    render(
      <QueryClientProvider client={new QueryClient()}>
        <RaidArena />
      </QueryClientProvider>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "레이드를 불러오지 못했습니다",
    );
  });
});

describe("BossBanner", () => {
  it("US5-AC7 살아 있는 보스의 감정과 남은 HP를 보이고 레이드 화면으로 잇는다", () => {
    renderWith(<BossBanner />, raid());

    const link = screen.getByRole("link", {
      name: /^레이드: 불안 보스가 나타났어요, HP 200\/300/,
    });
    expect(link).toHaveAttribute("href", "/raid");
    expect(link).toHaveTextContent("불안 보스가 나타났어요");
    expect(link).toHaveTextContent("5명이 함께하고");
  });

  it("보스가 끝났거나 없으면 아무것도 그리지 않는다", () => {
    renderWith(<BossBanner />, ended("DEFEATED"));
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});

describe("elapsedLabel", () => {
  it.each([
    ["2026-10-09T00:00:00Z", "2026-10-09T00:00:30Z", "1분 미만"],
    ["2026-10-09T00:00:00Z", "2026-10-09T00:05:59Z", "5분"],
    ["2026-10-09T00:00:00Z", "2026-10-09T02:30:00Z", "2시간 30분"],
    ["2026-10-09T00:00:00Z", "2026-10-11T03:10:00Z", "2일 3시간"],
  ])("%s에서 %s까지는 %s", (spawnedAt, endedAt, expected) => {
    expect(elapsedLabel(spawnedAt, endedAt)).toBe(expected);
  });
});

describe("레이드의 감정 색", () => {
  it("DS-US3-AC3 전투 무대는 보스의 감정 색이고 수치가 이름과 값으로 이어진다", () => {
    renderWith(<RaidArena />, raid());
    const boss = screen.getByRole("region", { name: "보스" });
    expect(boss).toHaveAttribute("data-emotion", "ANXIETY");
    expect(boss).toHaveClass("bg-emotion-tint");
    expect(boss).toHaveTextContent("함께한 회원5명");
    expect(boss).toHaveTextContent("내 기여3");
  });

  it("DS-US3-AC3 결과 무대도 보스의 감정 색이다", () => {
    renderWith(<RaidArena />, ended("DEFEATED"));
    const result = screen.getByRole("region", { name: "레이드 결과" });
    expect(result).toHaveAttribute("data-emotion", "ANXIETY");
    expect(result).toHaveClass("bg-emotion-tint");
  });

  it("DS-US3-AC4 쉬는 중 안내는 무대 안에서도 주의 색이다", () => {
    renderWith(<RaidArena />, raid({ available: false }));
    expect(screen.getByRole("status")).toHaveClass("text-warning");
  });

  it("DS-US3-AC3 홈의 보스 안내는 보스의 감정 색이다", () => {
    renderWith(<BossBanner />, raid());
    const link = screen.getByRole("link", {
      name: /^레이드: 불안 보스가 나타났어요, HP 200\/300/,
    });
    expect(link).toHaveAttribute("data-emotion", "ANXIETY");
    expect(link).toHaveClass("bg-emotion-tint");
  });

  it("DS-US3-AC6 결과의 수치는 좁은 화면에서 두 칸으로 나누고 값이 단어 중간에서 끊기지 않는다", () => {
    renderWith(<RaidArena />, ended("DEFEATED"));
    const result = screen.getByRole("region", { name: "레이드 결과" });
    const figures = within(result).getByText("걸린 시간").closest("dl");
    expect(figures).toHaveClass("grid", "grid-cols-2");
    expect(within(result).getByText("2시간 30분")).toHaveClass("break-keep");
    // 세 번째 칸은 좁은 화면에서 한 줄을 다 쓴다.
    expect(within(result).getByText("걸린 시간").parentElement).toHaveClass(
      "col-span-2",
      "sm:col-span-1",
    );
  });

  it("DS-US3-AC3 홈의 보스 안내는 큰 HP에 쉼표를 넣는다", () => {
    const state = raid();
    renderWith(<BossBanner />, {
      ...state,
      boss: { ...state.boss!, hp: 4120, maxHp: 10000 },
    });
    const link = screen.getByRole("link", { name: /^레이드: 불안 보스/ });
    expect(link).toHaveTextContent("HP 4,120/10,000");
  });

  it("006 US5-AC7 홈의 보스 안내는 눈에 보이는 글자를 이름에 그대로 담는다", () => {
    const state = raid();
    renderWith(<BossBanner />, {
      ...state,
      boss: { ...state.boss!, hp: 4120, maxHp: 10000 },
    });
    // 보이는 글자를 말해 누르는 음성 제어가 링크를 찾을 수 있어야 한다.
    expect(screen.getByRole("link")).toHaveAccessibleName(
      "레이드: 불안 보스가 나타났어요, HP 4,120/10,000, 5명이 함께하고 있어요, 함께 공격하기",
    );
  });

  it("006 US5-AC5 보스는 여러 날 살아 있을 수 있어 오늘의 보스라고 하지 않고, 제목은 보스까지 읽힌다", () => {
    renderWith(<RaidArena />, raid());
    const boss = screen.getByRole("region", { name: "보스" });
    expect(within(boss).queryByText("오늘의 보스")).not.toBeInTheDocument();
    expect(within(boss).getByText("지금의 보스")).toBeInTheDocument();
    expect(
      within(boss).getByRole("heading", { name: "불안 보스" }),
    ).toHaveClass("text-display");
  });

  it("DS-US3-AC3 홈의 보스 안내는 계약을 어긴 HP 값이 와도 죽지 않는다", () => {
    const state = raid();
    renderWith(<BossBanner />, {
      ...state,
      boss: {
        ...state.boss!,
        hp: undefined as unknown as number,
        maxHp: Number.NaN,
      },
    });
    expect(screen.getByRole("link")).toHaveTextContent("HP 0/0");
  });

  it("006 US5-AC1 수치는 바뀐 숫자만이 아니라 이름과 함께 읽힌다", () => {
    renderWith(<RaidArena />, raid());
    const figures = screen.getByText("함께한 회원").closest("dl");
    expect(figures).toHaveAttribute("aria-live", "polite");
    expect(figures).toHaveAttribute("aria-atomic", "true");
  });

  it("006 US5-AC6 쉬는 중이면 공격 버튼이 잠긴 까닭이 버튼과 이어져 있다", () => {
    renderWith(<RaidArena />, raid({ available: false }));
    expect(
      screen.getByRole("button", { name: "공격하기" }),
    ).toHaveAccessibleDescription(RAID_PAUSED_NOTICE);
  });
});
