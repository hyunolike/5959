/** 낮 20초 → 해질녘 4초 → 밤 20초 → 새벽 4초. 멈춘 판은 elapsed가 그대로다. */
export function nightAmount(elapsed: number, reducedMotion = false): number {
  const time = Math.max(0, elapsed) % 48;
  if (reducedMotion) return time >= 24 ? 1 : 0;
  const progress =
    time < 20
      ? 0
      : time < 24
        ? (time - 20) / 4
        : time < 44
          ? 1
          : (48 - time) / 4;
  return progress * progress * (3 - 2 * progress);
}
