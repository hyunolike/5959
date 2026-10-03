/** 몬스터 외형이 따르는 HP 단계. research.md R11, US5-AC2. */
export type HpStage = "full" | "hurt" | "weak" | "defeated";

/**
 * HP 비율에 따른 단계: `full`(2/3 초과), `hurt`(1/3 초과), `weak`(0 초과),
 * `defeated`(0). 정수 교차곱으로 비교해 10/20/30 어떤 maxHp에서도
 * 부동소수점 오차 없이 경계값(예: 20/30, 10/30)을 정확히 가른다.
 */
export function hpStage(hp: number, maxHp: number): HpStage {
  if (hp <= 0) return "defeated";
  if (hp * 3 > maxHp * 2) return "full";
  if (hp * 3 > maxHp) return "hurt";
  return "weak";
}

/**
 * [hpStage]와 같은 경계를 HP 비율로 받는다. research R11의 "66%, 33%"는 3등분의 반올림이라
 * 정확히 2/3(예: 20/30)은 `hurt`, 정확히 1/3(예: 10/30)은 `weak`다. `hp / maxHp`와
 * `2 / 3`은 같은 실수를 반올림한 같은 double이라 경계에서도 [hpStage]와 결과가 같다.
 */
export function hpStageOfRatio(hpRatio: number): HpStage {
  if (hpRatio <= 0) return "defeated";
  if (hpRatio > 2 / 3) return "full";
  if (hpRatio > 1 / 3) return "hurt";
  return "weak";
}
