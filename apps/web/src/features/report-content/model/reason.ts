import type { components } from "@/shared/api";

export type ReportReason = components["schemas"]["ReportReason"];
export type ReportTargetType = components["schemas"]["TargetType"];

/** 신고 사유와 화면에 보일 말(005 US3-AC1). 순서가 곧 보이는 순서다. */
export const REPORT_REASONS: readonly { value: ReportReason; label: string }[] =
  [
    { value: "DANGEROUS", label: "위험해 보여요" },
    { value: "ABUSIVE", label: "욕설이나 비방이에요" },
    { value: "SPAM", label: "광고나 도배예요" },
    { value: "OTHER", label: "기타" },
  ];

/** 기타를 골랐을 때 적는 설명의 글자 수 한도. 서버와 같이 사람이 보는 글자 단위로 센다. */
export const REPORT_DETAIL_MAX_LENGTH = 200;
