import type { components } from "@/shared/api";

export type RaidBoss = components["schemas"]["RaidBoss"];
export type RaidState = components["schemas"]["RaidState"];
export type RaidLive = components["schemas"]["RaidLive"];
export type RaidAttackResult = components["schemas"]["RaidAttackResult"];
export type RaidBossStatus = components["schemas"]["RaidBossStatus"];

/** 실시간 스트림에서 레이드 소식을 고르는 주제 이름이자 이벤트 이름(006 research R6). */
export const RAID_TOPIC = "raid";
