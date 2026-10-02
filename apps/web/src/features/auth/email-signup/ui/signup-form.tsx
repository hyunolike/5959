"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";

import { ApiError } from "@/shared/api";
import { withNextPath } from "@/shared/lib";
import { Button, Input, Label } from "@/shared/ui";

import { useSignupMutation } from "../api/use-signup-mutation";
import { pickSignup400Field } from "../model/pick-bad-request-field";
import { signupSchema, type SignupFormValues } from "../model/schema";

const GENERIC_ERROR_MESSAGE =
  "가입 중 문제가 생겼습니다. 잠시 후 다시 시도해주세요.";

/**
 * 이메일 가입 폼. 성공하면 `/onboarding`으로 이동한다(US1-AC1). 가입 화면에
 * 검증을 통과한 `next`가 있으면 온보딩을 마친 뒤 그곳으로 가도록 넘긴다. API 오류는
 * 필드에 인라인으로 보여준다: 409(이미 가입된 이메일)는 이메일 필드에
 * (US1-AC2). 400은 이메일 형식과 비밀번호 규칙 위반을 같은 코드로 뭉뚱그리므로
 * `pickSignup400Field`로 메시지 내용을 보고 이메일/비밀번호 필드를 가른다
 * (US1-AC3).
 */
interface SignupFormProps {
  /** 온보딩 뒤 돌아갈 경로. 검증은 withNextPath가 한다. */
  next?: string;
}

export function SignupForm({ next }: SignupFormProps) {
  const router = useRouter();
  const signupMutation = useSignupMutation();
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SignupFormValues>({ resolver: zodResolver(signupSchema) });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await signupMutation.mutateAsync(values);
      router.push(withNextPath("/onboarding", next));
    } catch (error) {
      if (error instanceof ApiError) {
        if (
          error.code === "EMAIL_ALREADY_REGISTERED" ||
          error.code === "EMAIL_REGISTERED_WITH_OTHER_METHOD"
        ) {
          setError("email", { message: error.message });
          return;
        }
        if (error.status === 400) {
          setError(pickSignup400Field(error.message), {
            message: error.message,
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
          autoComplete="new-password"
          {...register("password")}
        />
        {errors.password ? (
          <p role="alert" className="text-sm text-red-600">
            {errors.password.message}
          </p>
        ) : (
          <p className="text-xs text-neutral-500">
            영문과 숫자를 포함해 8~20자로 입력하세요.
          </p>
        )}
      </div>

      {errors.root && (
        <p role="alert" className="text-sm text-red-600">
          {errors.root.message}
        </p>
      )}

      <Button type="submit" disabled={isSubmitting}>
        가입하기
      </Button>
    </form>
  );
}
