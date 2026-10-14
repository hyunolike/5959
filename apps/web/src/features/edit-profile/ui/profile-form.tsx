"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { CircleNotch } from "@phosphor-icons/react";
import Link from "next/link";
import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";

import {
  CAREER_YEAR_LABELS,
  JOB_ROLE_LABELS,
  NICKNAME_REASON_LABEL,
  useNicknameCheck,
  type MemberProfile,
} from "@/entities/member";
import { ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";
import { cn } from "@/shared/lib";
import { Button, Input, Label, Select } from "@/shared/ui";

import { useUpdateProfileMutation } from "../api/use-update-profile-mutation";
import {
  NICKNAME_LOCK_NOTICE,
  nicknameLockedMessage,
  nicknameLockedUntil,
} from "../model/nickname-lock";
import {
  changedFields,
  editProfileSchema,
  hasChanges,
  isOwnNickname,
  type EditProfileFormValues,
} from "../model/schema";

const GENERIC_ERROR_MESSAGE =
  "프로필을 저장하지 못했습니다. 잠시 후 다시 시도해주세요.";

/** 화면을 열어 둔 사이 다른 기기에서 닉네임을 바꿨을 때(014 US2-AC4). */
const NICKNAME_LIMITED_MESSAGE =
  "닉네임을 바꾼 지 얼마 되지 않아 아직 다시 바꿀 수 없어요.";

const MY_PAGE = "/my";

/**
 * 프로필 수정 폼(004 US5): 닉네임, 직군, 경력. 지금 값으로 채워 두고 바뀐 항목만 보낸다.
 *
 * - 하나도 바꾸지 않았으면 저장 버튼을 막는다.
 * - 닉네임 규칙과 안내는 온보딩과 같다(US5-AC2). 닉네임을 바꿨을 때만 400ms 뒤 중복 여부를 미리 보인다.
 *   내 닉네임의 대소문자만 바꾼 경우는 확인하지 않는다(확인 API는 내가 쓰는 닉네임도 "사용 중"이라 답한다).
 * - 저장되면 마이페이지로 돌아간다(US5-AC1). 409와 400은 닉네임 필드에 보인다.
 * - 닉네임을 바꾸면 30일 동안 다시 바꿀 수 없다(014). 바꾸기 전에는 칸 아래에 그 안내를 보이고, 제한 중이면
 *   칸을 잠그고 언제부터 바꿀 수 있는지 보인다. 잠긴 칸은 `disabled`가 아니라 `readOnly`다. 초점을 받아야
 *   화면 낭독기가 값과 까닭을 읽고, 폼의 값에도 남아 "닉네임은 바뀌지 않았다"가 그대로 계산된다.
 *   제한 중에도 직군과 경력은 저장된다.
 */
export function ProfileForm({ member }: { member: MemberProfile }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const updateMutation = useUpdateProfileMutation();
  // 그리는 동안 시각이 바뀌지 않게 화면을 연 때로 고정한다. 그사이 제한이 풀렸으면 다시 열면 된다.
  const [openedAt] = useState(() => Date.now());
  const lockedUntil = nicknameLockedUntil(
    member.nicknameChangeAvailableAt,
    openedAt,
  );
  const nicknameLocked = lockedUntil !== null;
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
  // 내 닉네임이면 묻지 않는다(빈 값을 넘기면 확인하지 않는다). 잠긴 칸은 바뀔 일이 없다.
  const nicknameCheck = useNicknameCheck(
    ownNickname || nicknameLocked ? "" : nickname,
  );
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
        error.code === "NICKNAME_CHANGE_LIMITED"
      ) {
        setError("nickname", { message: NICKNAME_LIMITED_MESSAGE });
        // 내 정보를 다시 받아 칸을 잠그고 언제부터 바꿀 수 있는지 보인다.
        void queryClient.invalidateQueries({ queryKey: QUERY_KEYS.me });
        return;
      }
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
    <form onSubmit={onSubmit} noValidate className="flex w-full flex-col gap-5">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="nickname">닉네임</Label>
        <Input
          id="nickname"
          aria-required
          autoComplete="off"
          maxLength={10}
          aria-invalid={!!errors.nickname}
          aria-describedby={
            errors.nickname
              ? "nickname-rule nickname-lock nickname-error"
              : "nickname-rule nickname-lock nickname-status"
          }
          readOnly={nicknameLocked}
          className={cn(nicknameLocked && "bg-surface-sunken text-fg-muted")}
          {...register("nickname")}
        />
        <p id="nickname-rule" className="text-caption text-fg-subtle">
          한글, 영문, 숫자 1~10자
        </p>
        {/* 바꾸기 전에는 제한을 알리고, 제한 중이면 언제 풀리는지 알린다(014 US2-AC1, AC2). */}
        <p
          id="nickname-lock"
          className={cn(
            "text-caption break-keep",
            nicknameLocked ? "text-fg font-medium" : "text-fg-subtle",
          )}
        >
          {lockedUntil
            ? nicknameLockedMessage(lockedUntil)
            : NICKNAME_LOCK_NOTICE}
        </p>
        {errors.nickname ? (
          <p
            id="nickname-error"
            role="alert"
            className="text-body-sm text-danger"
          >
            {errors.nickname.message}
          </p>
        ) : null}
        {/* 확인 결과는 400ms 뒤 조용히 바뀐다. 자리를 처음부터 그려 두어 화면 낭독기가 읽게 한다. */}
        <p
          id="nickname-status"
          role="status"
          className={cn(
            "text-body-sm",
            settledNicknameResult?.available
              ? "text-fg font-medium"
              : "text-danger",
            !nicknameHint && "sr-only",
          )}
        >
          {nicknameHint}
        </p>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="jobRole">직군</Label>
        <Select id="jobRole" {...register("jobRole")}>
          {Object.entries(JOB_ROLE_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </Select>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="careerYear">경력</Label>
        <Select id="careerYear" {...register("careerYear")}>
          {Object.entries(CAREER_YEAR_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </Select>
        <p className="text-caption text-fg-subtle">
          직군과 경력을 바꿔도 예전에 쓴 글은 쓸 때의 값으로 보여요.
        </p>
      </div>

      {errors.root && (
        <p role="alert" className="text-body-sm text-danger">
          {errors.root.message}
        </p>
      )}

      <div className="border-line flex flex-col gap-2 border-t pt-4">
        {/* 저장이 왜 잠겨 있는지 버튼 옆에서 말한다. */}
        {!changed ? (
          <p id="profile-save-hint" className="text-caption text-fg-subtle">
            바꾼 내용이 있으면 저장할 수 있어요.
          </p>
        ) : null}
        <div className="grid grid-cols-2 gap-2 sm:flex sm:justify-end">
          <Button asChild variant="secondary" size="lg">
            <Link href={MY_PAGE}>취소</Link>
          </Button>
          <Button
            type="submit"
            size="lg"
            disabled={!changed || isSubmitting}
            aria-busy={isSubmitting}
            aria-describedby={!changed ? "profile-save-hint" : undefined}
            className="sm:min-w-24"
          >
            {isSubmitting ? (
              <CircleNotch
                aria-hidden
                size={16}
                className="motion-safe:animate-spin"
              />
            ) : null}
            저장
          </Button>
        </div>
      </div>
    </form>
  );
}
