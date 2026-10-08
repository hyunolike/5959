import {
  memberProfileSchema,
  type MemberProfileFormValues,
} from "@/entities/member";

/**
 * 온보딩 입력 규칙. 닉네임, 직군, 경력의 규칙은 회원 엔티티에 있고 프로필 수정(004 US5)과 함께 쓴다.
 */
export const onboardingSchema = memberProfileSchema;

export type OnboardingFormValues = MemberProfileFormValues;
