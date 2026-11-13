"use client";

import { useId, useState } from "react";

import styles from "./monster-formation.module.css";

/** 감정이 정해지기 전의 중립적인 모습. 누르기는 시각적 반응만 주고 분석 요청을 바꾸지 않는다. */
export function MonsterFormation() {
  const [touches, setTouches] = useState(0);
  const hintId = useId();

  return (
    <div className="flex flex-col items-center gap-3 text-center">
      <div className="relative flex h-44 w-full items-center justify-center sm:h-56">
        <button
          type="button"
          aria-label="마음 덩어리 살짝 건드리기"
          aria-describedby={hintId}
          onClick={() => setTouches((value) => value + 1)}
          className={`${styles.touch} focus-visible:outline-focus relative flex size-40 cursor-pointer items-center justify-center rounded-full focus-visible:outline-2 focus-visible:outline-offset-4`}
        >
          <span aria-hidden="true" className={styles.halo} />
          <span aria-hidden="true" className={styles.shadow} />
          <span
            key={touches}
            aria-hidden="true"
            className={touches > 0 ? styles.nudge : undefined}
          >
            <span className={styles.seed}>
              <span className={styles.face}>
                <span className={styles.eye} />
                <span className={styles.eye} />
              </span>
            </span>
            {touches > 0 ? <span className={styles.ripple} /> : null}
          </span>
          <span aria-hidden="true" className={styles.spark} />
          <span aria-hidden="true" className={styles.spark} />
          <span aria-hidden="true" className={styles.spark} />
        </button>
      </div>
      <div className="flex flex-col items-center gap-1">
        <p className="text-body-sm text-fg font-bold">
          마음이 모습을 찾고 있어요
        </p>
        <p className="text-label text-fg-subtle flex items-center gap-1.5">
          <span aria-hidden="true" className={styles.dots}>
            <span />
            <span />
            <span />
          </span>
          분석 중
        </p>
      </div>
      <p id={hintId} className="text-label text-fg-subtle">
        {touches > 0
          ? "톡! 조금만 기다려 주세요"
          : "기다리는 동안 살짝 눌러보세요"}
      </p>
    </div>
  );
}
