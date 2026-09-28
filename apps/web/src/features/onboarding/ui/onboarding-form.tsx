"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useForm, useWatch } from "react-hook-form";

import { CAREER_YEAR_LABELS, JOB_ROLE_LABELS } from "@/entities/member";
import { ApiError } from "@/shared/api";
import { cn } from "@/shared/lib";
import { Button, Input, Label } from "@/shared/ui";

import { useNicknameCheck } from "../api/use-nickname-check";
import { useOnboardingMutation } from "../api/use-onboarding-mutation";
import { onboardingSchema, type OnboardingFormValues } from "../model/schema";

const GENERIC_ERROR_MESSAGE =
  "온보딩을 완료하지 못했습니다. 잠시 후 다시 시도해주세요.";

const NICKNAME_REASON_LABEL: Record<"INVALID_FORMAT" | "TAKEN", string> = {
  INVALID_FORMAT: "한글, 영문, 숫자로 1~10자를 입력하세요.",
  TAKEN: "이미 사용 중인 닉네임입니다.",
};

const SELECT_CLASS_NAME =
  "h-10 w-full rounded-md border border-neutral-300 bg-white px-3 text-sm text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-50";

/**
 * 온보딩 폼(닉네임, 직군, 경력). 완료하면 `/home`으로 이동한다(US1-AC4).
 * 닉네임은 입력 뒤 400ms 디바운스로 중복 여부를 미리 보여준다(US1-AC5).
 * 저장 시 API 오류(409 NICKNAME_TAKEN, 400 형식 위반)는 닉네임 필드에
 * 인라인으로 보여준다(US1-AC5, US1-AC6).
 */
export function OnboardingForm() {
  const router = useRouter();
  const onboardingMutation = useOnboardingMutation();
  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors, isSubmitting },
  } = useForm<OnboardingFormValues>({
    resolver: zodResolver(onboardingSchema),
    defaultValues: { nickname: "", jobRole: undefined, careerYear: undefined },
  });

  const nickname = useWatch({ control, name: "nickname" }) ?? "";
  const nicknameCheck = useNicknameCheck(nickname);
  const nicknameUnavailable =
    nicknameCheck.data !== undefined && !nicknameCheck.data.available;

  const onSubmit = handleSubmit(async (values) => {
    if (nicknameUnavailable) {
      setError("nickname", {
        message: nicknameCheck.data?.reason
          ? NICKNAME_REASON_LABEL[nicknameCheck.data.reason]
          : "사용할 수 없는 닉네임입니다.",
      });
      return;
    }

    try {
      const member = await onboardingMutation.mutateAsync(values);
      if (member) {
        router.push("/home");
      }
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
    : nicknameCheck.data?.reason
      ? NICKNAME_REASON_LABEL[nicknameCheck.data.reason]
      : nicknameCheck.data?.available
        ? "사용할 수 있는 닉네임입니다."
        : null;

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      className="flex w-full max-w-sm flex-col gap-4"
    >
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
                nicknameCheck.data?.available
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
          defaultValue=""
          className={SELECT_CLASS_NAME}
          {...register("jobRole")}
        >
          <option value="" disabled>
            직군을 선택하세요
          </option>
          {Object.entries(JOB_ROLE_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {errors.jobRole && (
          <p role="alert" className="text-sm text-red-600">
            직군을 선택하세요.
          </p>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="careerYear">경력</Label>
        <select
          id="careerYear"
          defaultValue=""
          className={SELECT_CLASS_NAME}
          {...register("careerYear")}
        >
          <option value="" disabled>
            경력을 선택하세요
          </option>
          {Object.entries(CAREER_YEAR_LABELS).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {errors.careerYear && (
          <p role="alert" className="text-sm text-red-600">
            경력을 선택하세요.
          </p>
        )}
      </div>

      {errors.root && (
        <p role="alert" className="text-sm text-red-600">
          {errors.root.message}
        </p>
      )}

      <Button type="submit" disabled={isSubmitting}>
        완료
      </Button>
    </form>
  );
}
