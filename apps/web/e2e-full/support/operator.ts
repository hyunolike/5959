import type { APIRequestContext, APIResponse } from "@playwright/test";

import { runSql } from "./db";

/**
 * 운영자는 화면도 가입 절차도 없다. DB에서 지정하고 API를 직접 부른다(005 research R10). 테스트도 같은 길을 쓴다.
 */
const API_ORIGIN = process.env.API_ORIGIN ?? "http://localhost:18080";

/** 이 이메일로 가입한 회원을 운영자로 지정한다. 한 명이 바뀌지 않으면 실패한다. */
export function grantOperator(email: string): void {
  if (!/^[\w.+-]+@[\w.-]+$/.test(email)) {
    throw new Error(`운영자로 지정할 이메일이 올바르지 않습니다: ${email}`);
  }
  const output = runSql(
    `update member set role = 'OPERATOR' where email = '${email}'`,
  );
  if (!output.includes("UPDATE 1")) {
    throw new Error(`운영자로 지정하지 못했습니다: ${output}`);
  }
}

export interface OperatorAccount {
  email: string;
  password: string;
}

/**
 * 운영자로 API를 직접 부른다. BFF는 `operator/**`를 넘기지 않는다. e2e의 access 토큰은 5초라
 * 부를 때마다 새로 로그인한다.
 */
export async function callAsOperator(
  request: APIRequestContext,
  operator: OperatorAccount,
  method: "GET" | "PUT" | "POST" | "DELETE",
  operatorPath: string,
  data?: unknown,
): Promise<APIResponse> {
  const login = await request.post(`${API_ORIGIN}/api/v1/auth/login`, {
    data: operator,
  });
  if (login.status() !== 200) {
    throw new Error(`운영자 로그인 실패: ${login.status()}`);
  }
  const { data: session } = (await login.json()) as {
    data: { tokens: { accessToken: string } };
  };
  return request.fetch(`${API_ORIGIN}/api/v1/operator${operatorPath}`, {
    method,
    headers: { Authorization: `Bearer ${session.tokens.accessToken}` },
    data,
  });
}
