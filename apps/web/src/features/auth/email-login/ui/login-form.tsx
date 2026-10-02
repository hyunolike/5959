"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";

import { ApiError } from "@/shared/api";
import { Button, Input, Label } from "@/shared/ui";

import { useLoginMutation } from "../api/use-login-mutation";
import { loginDestination } from "../model/login-destination";
import { retryWaitMinutes } from "../model/retry-wait-minutes";
import { loginSchema, type LoginFormValues } from "../model/schema";

const GENERIC_ERROR_MESSAGE =
  "로그인 중 문제가 생겼습니다. 잠시 후 다시 시도해주세요.";

/**
 * 이메일 로그인 폼. 성공하면 온보딩 여부에 따라 `/home` 또는 `/onboarding`으로
 * 이동한다(US2-AC1). 로그인 화면에 `next`가 있으면 검증한 뒤 그곳으로
 * 돌아간다(US4-AC5). 401(비밀번호가
 * 틀렸거나 가입되지 않은 이메일)은 어느 쪽인지 구분하지 않는 같은 메시지를
 * 보여준다(US2-AC2). 429(로그인 실패 제한)는 남은 시간을 분 단위로 안내한다
 * (US2-AC3, US2-AC4).
 */
interface LoginFormProps {
  /** 로그인 뒤 돌아갈 경로. 검증은 loginDestination이 한다. */
  next?: string;
}

export function LoginForm({ next }: LoginFormProps) {
  const router = useRouter();
  const loginMutation = useLoginMutation();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginFormValues>({ resolver: zodResolver(loginSchema) });

  const onSubmit = handleSubmit(async (values) => {
    try {
      const member = await loginMutation.mutateAsync(values);
      router.push(loginDestination(member.onboarded, next));
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === "INVALID_CREDENTIALS") {
          setError("root", { message: error.message });
          return;
        }
        if (error.code === "LOGIN_THROTTLED") {
          const minutes = retryWaitMinutes(error.retryAfterSeconds ?? 0);
          setError("root", {
            message: `로그인 시도가 너무 많습니다. ${minutes}분 후 다시 시도해주세요.`,
          });
          return;
        }
      }
      setError("root", { message: GENERIC_ERROR_MESSAGE });
    }
  });

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      className="flex w-full max-w-sm flex-col gap-4"
    >
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="email">이메일</Label>
        <Input
          id="email"
          type="email"
          autoComplete="email"
          {...register("email")}
        />
        {errors.email && (
          <p role="alert" className="text-sm text-red-600">
            {errors.email.message}
          </p>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="password">비밀번호</Label>
        <Input
          id="password"
          type="password"
          autoComplete="current-password"
          {...register("password")}
        />
        {errors.password && (
          <p role="alert" className="text-sm text-red-600">
            {errors.password.message}
          </p>
        )}
      </div>

      {errors.root && (
        <p role="alert" className="text-sm text-red-600">
          {errors.root.message}
        </p>
      )}

      <Button type="submit" disabled={isSubmitting}>
        로그인
      </Button>
    </form>
  );
}
