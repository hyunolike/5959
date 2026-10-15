"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useForm, useWatch } from "react-hook-form";

import {
  CAREER_YEAR_LABELS,
  JOB_ROLE_LABELS,
  NICKNAME_REASON_LABEL,
  useNicknameCheck,
} from "@/entities/member";
import { ApiError } from "@/shared/api";
import { cn, sanitizeNextPath } from "@/shared/lib";
import { Button, Input, Label, Select } from "@/shared/ui";

import { useOnboardingMutation } from "../api/use-onboarding-mutation";
import { onboardingSchema, type OnboardingFormValues } from "../model/schema";

/** 홈이 온보딩 직후 한 번 환영 안내를 보이게 하는 표시(016 research R2). 홈이 읽자마자 지운다. */
const WELCOME_PENDING_KEY = "ogu.welcome.pending";

function markWelcomePending() {
  try {
    sessionStorage.setItem(WELCOME_PENDING_KEY, "1");
  } catch {
    // 저장소가 막힌 브라우저에서는 환영 안내 없이 홈으로 간다.
  }
}

const GENERIC_ERROR_MESSAGE =
  "온보딩을 완료하지 못했습니다. 잠시 후 다시 시도해주세요.";

/**
 * 온보딩 폼(닉네임, 직군, 경력). 완료하면 `/home`으로 이동한다(US1-AC4).
 * 닉네임은 입력 뒤 400ms 디바운스로 중복 여부를 미리 보여준다(US1-AC5).
 * 저장 시 API 오류(409 NICKNAME_TAKEN, 400 형식 위반)는 닉네임 필드에
 * 인라인으로 보여준다(US1-AC5, US1-AC6).
 */
interface OnboardingFormProps {
  /** 온보딩 뒤 갈 경로. 검증을 통과하지 못하면 `/home`이다. */
  next?: string;
}

export function OnboardingForm({ next }: OnboardingFormProps) {
  const router = useRouter();
  const destination = sanitizeNextPath(next);
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
  // isSettled가 false면 data는 지금 입력이 아니라 이전 값의 결과다(디바운스가
  // 아직 끝나지 않았다). 그럴 때는 힌트도, 제출 차단도 하지 않고 서버의
  // 409(NICKNAME_TAKEN) 응답에 맡긴다 — 오래된 "사용 중" 결과로 방금 고쳐
  // 쓴 사용 가능한 닉네임을 막으면 안 된다.
  const settledNicknameResult = nicknameCheck.isSettled
    ? nicknameCheck.data
    : undefined;
  const nicknameUnavailable =
    settledNicknameResult !== undefined && !settledNicknameResult.available;

  const onSubmit = handleSubmit(async (values) => {
    if (nicknameUnavailable) {
      setError("nickname", {
        message: settledNicknameResult?.reason
          ? NICKNAME_REASON_LABEL[settledNicknameResult.reason]
          : "사용할 수 없는 닉네임입니다.",
      });
      return;
    }

    try {
      const member = await onboardingMutation.mutateAsync(values);
      if (member) {
        // 가려던 곳이 있으면 그곳이 먼저다. 환영 안내는 홈으로 올 때만 남긴다(016 US2-AC4).
        if (destination === "/home") markWelcomePending();
        router.push(destination);
        // 온보딩 쿠키가 생겼다. 루트 레이아웃을 새로 받아 알림 종을 그린다.
        router.refresh();
      }
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === "ALREADY_ONBOARDED") {
          router.push(destination);
          router.refresh();
          return;
        }
        if (error.code === "NICKNAME_TAKEN" || error.status === 400) {
          setError("nickname", { message: error.message });
          return;
        }
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
    <form
      onSubmit={onSubmit}
      noValidate
      className="flex w-full max-w-sm flex-col gap-4"
    >
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="nickname">닉네임</Label>
        <Input
          id="nickname"
          aria-required
          autoComplete="off"
          maxLength={10}
          aria-invalid={errors.nickname ? true : undefined}
          aria-describedby={
            errors.nickname
              ? "nickname-rule nickname-error"
              : "nickname-rule nickname-status"
          }
          {...register("nickname")}
        />
        <p id="nickname-rule" className="text-caption text-fg-subtle">
          한글, 영문, 숫자 1~10자
        </p>
        {errors.nickname ? (
          <p
            id="nickname-error"
            role="alert"
            className="text-label text-danger"
          >
            {errors.nickname.message}
          </p>
        ) : null}
        {/* 확인 결과는 400ms 뒤 조용히 바뀐다. 자리를 처음부터 그려 두어 화면 낭독기가 읽게 한다. */}
        <p
          id="nickname-status"
          role="status"
          className={cn(
            "text-label",
            settledNicknameResult?.available
              ? "text-fg font-medium"
              : "text-danger",
            !nicknameHint && "sr-only",
          )}
        >
          {nicknameHint}
        </p>
      </div>

      {/* 고민 서비스에서 직군과 경력을 물으면 "나를 알아볼 수 있나"로 읽힌다. 까닭을 먼저 말한다(016 US2-AC1). */}
      <p
        id="profile-purpose"
        className="text-body-sm text-fg-subtle -mb-1 leading-6 break-keep"
      >
        비슷한 일을 하는 사람의 고민을 찾고 내 글 옆에 함께 보여요. 이름과
        회사는 묻지 않아요.
      </p>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="jobRole">직군</Label>
        <Select
          id="jobRole"
          aria-required
          defaultValue=""
          aria-invalid={errors.jobRole ? true : undefined}
          aria-describedby={
            errors.jobRole ? "jobRole-error" : "profile-purpose"
          }
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
        </Select>
        {errors.jobRole && (
          <p id="jobRole-error" role="alert" className="text-label text-danger">
            직군을 선택하세요.
          </p>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="careerYear">경력</Label>
        <Select
          id="careerYear"
          aria-required
          defaultValue=""
          aria-invalid={errors.careerYear ? true : undefined}
          aria-describedby={
            errors.careerYear ? "careerYear-error" : "profile-purpose"
          }
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
        </Select>
        {errors.careerYear && (
          <p
            id="careerYear-error"
            role="alert"
            className="text-label text-danger"
          >
            경력을 선택하세요.
          </p>
        )}
      </div>

      {errors.root && (
        <p role="alert" className="text-body-sm text-danger">
          {errors.root.message}
        </p>
      )}

      <Button type="submit" size="lg" disabled={isSubmitting}>
        완료
      </Button>
    </form>
  );
}
