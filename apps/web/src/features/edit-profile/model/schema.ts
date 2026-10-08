import {
  memberProfileSchema,
  type MemberProfile,
  type MemberProfileFormValues,
} from "@/entities/member";

/**
 * 프로필 수정 입력 규칙(004 US5). M1 온보딩과 같은 스키마라 같은 닉네임이 같은 결과를 낸다(US5-AC2).
 */
export const editProfileSchema = memberProfileSchema;

export type EditProfileFormValues = MemberProfileFormValues;

/** `PATCH /api/members/me`의 본문. 바뀐 항목만 담는다. */
export type ProfileChanges = Partial<EditProfileFormValues>;

/**
 * 지금 프로필과 다른 항목만 고른다. 닉네임은 앞뒤 공백을 뺀 값으로 비교하고, 대소문자만 바꿔도 바뀐 것이다.
 * 아무것도 바뀌지 않았으면 빈 객체다.
 */
export function changedFields(
  current: Pick<MemberProfile, "nickname" | "jobRole" | "careerYear">,
  values: EditProfileFormValues,
): ProfileChanges {
  const changes: ProfileChanges = {};
  const nickname = values.nickname.trim();
  if (nickname !== current.nickname) {
    changes.nickname = nickname;
  }
  if (values.jobRole !== current.jobRole) {
    changes.jobRole = values.jobRole;
  }
  if (values.careerYear !== current.careerYear) {
    changes.careerYear = values.careerYear;
  }
  return changes;
}

/** 바뀐 항목이 하나라도 있는가. 없으면 저장 버튼을 막는다. */
export function hasChanges(changes: ProfileChanges): boolean {
  return Object.keys(changes).length > 0;
}

/**
 * 닉네임을 내 지금 닉네임에서 대소문자만 바꿨는가. 닉네임 확인 API는 이 경우에도 "사용 중"이라고 답하므로
 * (내가 쓰고 있다) 미리 확인하지 않고 저장에 맡긴다. 서버는 내 닉네임의 대소문자 변경을 허용한다.
 */
export function isOwnNickname(
  currentNickname: string | null | undefined,
  input: string,
): boolean {
  return (
    !!currentNickname &&
    input.trim().toLowerCase() === currentNickname.toLowerCase()
  );
}
