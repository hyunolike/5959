"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";

import { ApiError } from "@/shared/api";
import { Button, Input, Label } from "@/shared/ui";

import { useConfirmPasswordResetMutation } from "../api/password-reset";
import {
  resetPasswordSchema,
  type ResetPasswordFormValues,
} from "../model/schema";

const GENERIC_ERROR_MESSAGE =
  "비밀번호를 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.";

/** 저장하고 로그인 화면으로 갈 때. 주소를 바꿔 끼워 뒤로 가기에 링크의 값이 남지 않는다. */
function replaceLocation(to: string): void {
  window.location.replace(to);
}

interface ResetPasswordFormProps {
  /** 메일의 링크에 든 값. */
  token: string;
  /** 화면을 떠나는 방법. 테스트가 바꿔 끼운다. */
  navigate?: (to: string) => void;
}

/**
 * 링크에서 새 비밀번호를 정한다(015 US2). 두 칸에 같은 비밀번호를 적고 저장하면 로그인 화면으로 간다. 로그인시키지
 * 않는다. 링크를 쓸 수 없으면(만료, 이미 씀, 새 요청에 밀려남) 까닭을 가르지 않고 다시 요청하는 길을 보인다
 * (US2-AC3, AC4, AC9). 규칙에 맞지 않는 비밀번호는 링크를 쓰지 않으므로 고쳐서 다시 저장한다(US2-AC6).
 */
export function ResetPasswordForm({
  token,
  navigate = replaceLocation,
}: ResetPasswordFormProps) {
  const mutation = useConfirmPasswordResetMutation();
  const [linkInvalid, setLinkInvalid] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordFormValues>({
    resolver: zodResolver(resetPasswordSchema),
  });

  const onSubmit = handleSubmit(async ({ password }) => {
    try {
      await mutation.mutateAsync({ token, password });
      navigate("/login?reset=1");
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === "PASSWORD_RESET_LINK_INVALID") {
          setLinkInvalid(true);
          return;
        }
        if (error.status === 400) {
          setError("password", { message: error.message });
          return;
        }
      }
      setError("root", { message: GENERIC_ERROR_MESSAGE });
    }
  });

  if (linkInvalid) {
    return <InvalidLinkNotice />;
  }

  return (
    <form onSubmit={onSubmit} noValidate className="flex w-full flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="new-password">새 비밀번호</Label>
        <Input
          id="new-password"
          aria-required
          type="password"
          autoComplete="new-password"
          aria-invalid={errors.password ? true : undefined}
          aria-describedby={
            errors.password ? "new-password-error" : "new-password-hint"
          }
          {...register("password")}
        />
        {errors.password ? (
          <p
            id="new-password-error"
            role="alert"
            className="text-label text-danger"
          >
            {errors.password.message}
          </p>
        ) : (
          <p id="new-password-hint" className="text-label text-fg-subtle">
            영문과 숫자를 포함해 8~20자
          </p>
        )}
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="new-password-confirm">새 비밀번호 확인</Label>
        <Input
          id="new-password-confirm"
          aria-required
          type="password"
          autoComplete="new-password"
          aria-invalid={errors.passwordConfirm ? true : undefined}
          aria-describedby={
            errors.passwordConfirm ? "new-password-confirm-error" : undefined
          }
          {...register("passwordConfirm")}
        />
        {errors.passwordConfirm ? (
          <p
            id="new-password-confirm-error"
            role="alert"
            className="text-label text-danger"
          >
            {errors.passwordConfirm.message}
          </p>
        ) : null}
      </div>
      {errors.root ? (
        <p role="alert" className="text-body-sm text-danger break-keep">
          {errors.root.message}
        </p>
      ) : null}
      <p className="text-label text-fg-subtle break-keep">
        저장하면 로그인해 있던 모든 기기에서 로그아웃돼요.
      </p>
      <Button type="submit" size="lg" disabled={isSubmitting}>
        비밀번호 바꾸기
      </Button>
    </form>
  );
}

/** 쓸 수 없는 링크. 만료, 이미 씀, 새 요청에 밀려남을 가르지 않는다(015 FR-007). */
export function InvalidLinkNotice() {
  return (
    <div role="alert" className="flex flex-col gap-3">
      <p className="text-body text-fg font-bold break-keep">
        쓸 수 없는 링크예요.
      </p>
      <p className="text-body-sm text-fg-muted break-keep">
        링크는 30분 동안, 한 번만 쓸 수 있어요. 여러 번 요청했다면 마지막
        요청으로 받은 메일의 링크만 쓸 수 있어요.
      </p>
      <Link
        href="/forgot-password"
        className="text-body-sm text-fg inline-flex min-h-11 items-center font-bold underline underline-offset-2"
      >
        재설정 메일 다시 받기
      </Link>
    </div>
  );
}
