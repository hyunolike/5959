"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";

import { ApiError } from "@/shared/api";
import { Button, Input, Label } from "@/shared/ui";

import { useRequestPasswordResetMutation } from "../api/password-reset";
import {
  requestResetSchema,
  waitMinutes,
  type RequestResetFormValues,
} from "../model/schema";

const GENERIC_ERROR_MESSAGE =
  "요청을 보내지 못했어요. 잠시 후 다시 시도해 주세요.";
const UNAVAILABLE_MESSAGE =
  "지금은 비밀번호 재설정 메일을 보낼 수 없어요. 잠시 후 다시 시도해 주세요.";

/**
 * 비밀번호 재설정 메일 요청(015 US1). 이메일 한 칸이다. 보내면 폼 자리에 안내가 온다. 안내는 가입 여부와 상관없이
 * 같다. "메일을 보냈다"가 아니라 "가입한 이메일이라면 메일이 간다"고 말한다(FR-002). 넣은 이메일을 다시 보여 주되
 * 가입돼 있다고 말하지 않는다.
 *
 * 같은 곳에서 너무 자주 요청하면(429) 남은 시간을 분으로 알린다(US3-AC2). 메일 발송이 꺼져 있으면(503) 지금은 쓸 수
 * 없다고 알린다(US4-AC2).
 */
export function RequestResetForm() {
  const mutation = useRequestPasswordResetMutation();
  const [sentTo, setSentTo] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RequestResetFormValues>({
    resolver: zodResolver(requestResetSchema),
  });

  const onSubmit = handleSubmit(async ({ email }) => {
    try {
      await mutation.mutateAsync(email);
      setSentTo(email.trim());
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.code === "PASSWORD_RESET_THROTTLED") {
          const minutes = waitMinutes(error.retryAfterSeconds);
          setError("root", {
            message: `요청이 너무 많아요. ${minutes}분 뒤에 다시 시도해 주세요.`,
          });
          return;
        }
        if (error.code === "PASSWORD_RESET_UNAVAILABLE") {
          setError("root", { message: UNAVAILABLE_MESSAGE });
          return;
        }
        if (error.status === 400) {
          setError("email", { message: "이메일을 다시 확인해 주세요." });
          return;
        }
      }
      setError("root", { message: GENERIC_ERROR_MESSAGE });
    }
  });

  if (sentTo !== null) {
    return (
      <div role="status" className="flex flex-col gap-3">
        <p className="text-body text-fg font-bold break-keep">
          {sentTo}이 가입한 이메일이라면 재설정 메일이 가요.
        </p>
        <ul className="text-body-sm text-fg-muted flex list-disc flex-col gap-1 pl-5 break-keep">
          <li>메일의 링크는 30분 동안, 한 번만 쓸 수 있어요.</li>
          <li>몇 분이 지나도 메일이 없으면 스팸함을 봐 주세요.</li>
          <li>카카오나 구글로 가입했다면 그 버튼으로 로그인해 주세요.</li>
        </ul>
        <Link
          href="/login"
          className="text-body-sm text-fg mt-2 inline-flex min-h-11 items-center font-bold underline underline-offset-2"
        >
          로그인 화면으로
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate className="flex w-full flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="reset-email">가입한 이메일</Label>
        <Input
          id="reset-email"
          aria-required
          type="email"
          autoComplete="email"
          aria-invalid={errors.email ? true : undefined}
          aria-describedby={errors.email ? "reset-email-error" : undefined}
          {...register("email")}
        />
        {errors.email ? (
          <p
            id="reset-email-error"
            role="alert"
            className="text-label text-danger"
          >
            {errors.email.message}
          </p>
        ) : null}
      </div>
      {errors.root ? (
        <p role="alert" className="text-body-sm text-danger break-keep">
          {errors.root.message}
        </p>
      ) : null}
      <Button type="submit" size="lg" disabled={isSubmitting}>
        재설정 메일 받기
      </Button>
    </form>
  );
}
