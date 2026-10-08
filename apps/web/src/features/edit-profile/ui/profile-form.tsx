"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm, useWatch } from "react-hook-form";

import {
  CAREER_YEAR_LABELS,
  JOB_ROLE_LABELS,
  NICKNAME_REASON_LABEL,
  useNicknameCheck,
  type MemberProfile,
} from "@/entities/member";
import { ApiError } from "@/shared/api";
import { cn } from "@/shared/lib";
import { Button, Input, Label } from "@/shared/ui";

import { useUpdateProfileMutation } from "../api/use-update-profile-mutation";
import {
  changedFields,
  editProfileSchema,
  hasChanges,
  isOwnNickname,
  type EditProfileFormValues,
} from "../model/schema";

const GENERIC_ERROR_MESSAGE =
  "프로필을 저장하지 못했습니다. 잠시 후 다시 시도해주세요.";

const SELECT_CLASS_NAME =
  "h-10 w-full rounded-md border border-neutral-300 bg-white px-3 text-sm text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50";

const MY_PAGE = "/my";

/**
 * 프로필 수정 폼(004 US5): 닉네임, 직군, 경력. 지금 값으로 채워 두고 바뀐 항목만 보낸다.
 *
 * - 하나도 바꾸지 않았으면 저장 버튼을 막는다.
 * - 닉네임 규칙과 안내는 온보딩과 같다(US5-AC2). 닉네임을 바꿨을 때만 400ms 뒤 중복 여부를 미리 보인다.
 *   내 닉네임의 대소문자만 바꾼 경우는 확인하지 않는다(확인 API는 내가 쓰는 닉네임도 "사용 중"이라 답한다).
 * - 저장되면 마이페이지로 돌아간다(US5-AC1). 409와 400은 닉네임 필드에 보인다.
 */
export function ProfileForm({ member }: { member: MemberProfile }) {
  const router = useRouter();
  const updateMutation = useUpdateProfileMutation();
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors, isSubmitting },
  } = useForm<EditProfileFormValues>({
    resolver: zodResolver(editProfileSchema),
    defaultValues: {
      nickname: member.nickname ?? "",
      jobRole: member.jobRole ?? undefined,
      careerYear: member.careerYear ?? undefined,
    },
  });

  const values = useWatch({ control });
  const nickname = values.nickname ?? "";
  const ownNickname = isOwnNickname(member.nickname, nickname);
  // 내 닉네임이면 묻지 않는다(빈 값을 넘기면 확인하지 않는다).
  const nicknameCheck = useNicknameCheck(ownNickname ? "" : nickname);
  // isSettled가 false면 data는 지금 입력이 아니라 이전 값의 결과다. 그때는 힌트도 차단도 하지 않는다.
  const settledNicknameResult =
    !ownNickname && nicknameCheck.isSettled ? nicknameCheck.data : undefined;
  const nicknameUnavailable =
    settledNicknameResult !== undefined && !settledNicknameResult.available;

  const changed = hasChanges(
    changedFields(member, {
      nickname,
      jobRole: values.jobRole ?? member.jobRole!,
      careerYear: values.careerYear ?? member.careerYear!,
    }),
  );

  const onSubmit = handleSubmit(async (submitted) => {
    const changes = changedFields(member, submitted);
    if (!hasChanges(changes)) {
      return;
    }
    if (changes.nickname !== undefined && nicknameUnavailable) {
      setError("nickname", {
        message: settledNicknameResult?.reason
          ? NICKNAME_REASON_LABEL[settledNicknameResult.reason]
          : "사용할 수 없는 닉네임입니다.",
      });
      return;
    }

    try {
      await updateMutation.mutateAsync(changes);
      router.push(MY_PAGE);
    } catch (error) {
      if (
        error instanceof ApiError &&
        (error.code === "NICKNAME_TAKEN" || error.status === 400)
      ) {
        setError("nickname", { message: error.message });
        return;
      }
      setError("root", { message: GENERIC_ERROR_MESSAGE });
    }
  });

  const nicknameHint = errors.nickname
    ? null
    : settledNicknameResult?.reason
      ? NICKNAME_REASON_LABEL[settledNicknameResult.reason]
      : settledNicknameResult?.available
        ? "사용할 수 있는 닉네임입니다."
        : null;

  return (
    <form onSubmit={onSubmit} noValidate className="flex w-full flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="nickname">닉네임</Label>
        <Input
          id="nickname"
          autoComplete="off"
          maxLength={10}
          {...register("nickname")}
        />
        {errors.nickname ? (
          <p role="alert" className="text-sm text-red-600">
            {errors.nickname.message}
          </p>
        ) : (
          nicknameHint && (
            <p
              className={cn(
                "text-sm",
                settledNicknameResult?.available
                  ? "text-emerald-600"
                  : "text-neutral-500",
              )}
            >
              {nicknameHint}
            </p>
          )
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="jobRole">직군</Label>
        <select
          id="jobRole"
          className={SELECT_CLASS_NAME}
          {...register("jobRole")}
        >
          {Object.entries(JOB_ROLE_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="careerYear">경력</Label>
        <select
          id="careerYear"
          className={SELECT_CLASS_NAME}
          {...register("careerYear")}
        >
          {Object.entries(CAREER_YEAR_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <p className="text-xs text-neutral-500">
          직군과 경력을 바꿔도 예전에 쓴 글은 쓸 때의 값으로 보여요.
        </p>
      </div>

      {errors.root && (
        <p role="alert" className="text-sm text-red-600">
          {errors.root.message}
        </p>
      )}

      <div className="flex justify-end gap-2">
        <Button asChild variant="outline">
          <Link href={MY_PAGE}>취소</Link>
        </Button>
        <Button type="submit" disabled={!changed || isSubmitting}>
          저장
        </Button>
      </div>
    </form>
  );
}
