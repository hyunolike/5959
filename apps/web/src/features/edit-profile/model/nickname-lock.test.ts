import { describe, expect, it } from "vitest";

import {
  NICKNAME_LOCK_NOTICE,
  nicknameLockedMessage,
  nicknameLockedUntil,
} from "./nickname-lock";

const NOW = Date.parse("2026-10-14T03:00:00Z");

describe("nicknameLockedUntil", () => {
  it("값이 없으면 제한이 없다", () => {
    expect(nicknameLockedUntil(null, NOW)).toBeNull();
    expect(nicknameLockedUntil(undefined, NOW)).toBeNull();
  });

  it("지난 때나 지금과 같은 때면 제한이 없다", () => {
    expect(nicknameLockedUntil("2026-10-14T02:59:59Z", NOW)).toBeNull();
    expect(nicknameLockedUntil("2026-10-14T03:00:00Z", NOW)).toBeNull();
  });

  it("앞으로의 때면 그때까지 제한 중이다", () => {
    const until = nicknameLockedUntil("2026-11-13T03:00:00Z", NOW);
    expect(until?.toISOString()).toBe("2026-11-13T03:00:00.000Z");
  });

  it("읽을 수 없는 값은 제한이 없는 것으로 본다", () => {
    expect(nicknameLockedUntil("곧", NOW)).toBeNull();
  });
});

describe("안내 문구", () => {
  it("US2-AC1 바꾸기 전의 안내는 30일을 말한다", () => {
    expect(NICKNAME_LOCK_NOTICE).toBe(
      "닉네임을 바꾸면 30일 동안 다시 바꿀 수 없어요.",
    );
  });

  it("US2-AC2 끝나는 때를 한국 시간의 날짜와 시각으로 말한다", () => {
    // 11월 12일 23:30(UTC)은 한국 시간으로 11월 13일 08:30이다
    expect(nicknameLockedMessage(new Date("2026-11-12T23:30:00Z"))).toBe(
      "닉네임은 11월 13일 08:30부터 다시 바꿀 수 있어요.",
    );
    expect(nicknameLockedMessage(new Date("2026-11-13T06:05:00Z"))).toBe(
      "닉네임은 11월 13일 15:05부터 다시 바꿀 수 있어요.",
    );
  });
});
