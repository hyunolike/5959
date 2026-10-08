/**
 * Sentry `beforeSend`/`beforeBreadcrumb`에 붙이는 순수 스크러빙 함수.
 *
 * `sentry.server.config.ts`, `sentry.edge.config.ts`, `instrumentation-client.ts`
 * 세 곳 모두 이 함수를 쓴다. Next.js/Sentry SDK 타입에 의존하지 않고, 우리가
 * 실제로 다루는 필드만 구조적으로 선언해 둔다(SDK가 주는 `Event`/`Breadcrumb`는
 * 이 타입들의 부분집합이 아니라 상위집합이라 구조적으로 그대로 대입된다).
 *
 * 지우는 것:
 * - 요청 쿠키(헤더의 `Cookie`/`Set-Cookie`, `request.cookies` 필드)
 * - `Authorization`, `X-Ogu-Bff-Key` 헤더
 * - `password` 필드(객체든, JSON 문자열이든, 몇 겹 중첩되어 있든)
 * - OAuth 콜백 URL의 `code`, `state` 쿼리 파라미터
 * - 실시간 알림 스트림 주소의 일회용 `ticket` 쿼리 파라미터
 * - `event.user` 전체(SDK의 `dataCollection.userInfo` 기본값이 `true`라
 *   `event.user.ip_address` 등이 자동으로 채워질 수 있다 — Sentry.init
 *   쪽에서 `userInfo: false`로 막아도, 이중 방어로 여기서도 지운다)
 * - `request.env`(런타임이 채워 넣는, 감사되지 않은 서버 환경값)
 * - `event.exception.values[].stacktrace.frames[].vars`,
 *   `event.threads.values[].stacktrace.frames[].vars` 전체(SDK의
 *   `dataCollection.stackFrameVariables` 기본값이 `true`라
 *   `localVariablesIntegration`이 스택 프레임마다 지역 변수 값을 채워
 *   넣는다 — 예를 들어 로그인 함수가 던진 오류의 프레임에 `password`
 *   지역 변수 값이 그대로 잡힌다. 번들러가 변수명을 바꿔버릴 수 있어
 *   이름으로 거르지 않고 `vars` 자체를 통째로 지운다)
 *
 * 위 항목은 이벤트의 `request`, `breadcrumbs[].data`, `extra`, `contexts`,
 * `exception`, `threads` 어디에 있든 지운다.
 */

const REDACTED = "[Filtered]";

/** 대소문자를 가리지 않고 지우는 키. 쿠키/인증/비밀번호 관련 키만 담는다. */
const SENSITIVE_KEYS = new Set([
  "cookie",
  "cookies",
  "authorization",
  "x-ogu-bff-key",
  "set-cookie",
  "password",
]);

/** URL에서 지우는 쿼리 파라미터. OAuth 콜백의 인가 코드/상태값. */
const STRIPPED_QUERY_PARAMS = ["code", "state", "ticket"];

export type ScrubbableQueryString =
  string | Record<string, string> | Array<[string, string]>;

export interface ScrubbableRequestData {
  url?: string;
  headers?: Record<string, string>;
  cookies?: Record<string, string>;
  data?: unknown;
  query_string?: ScrubbableQueryString;
  env?: Record<string, string>;
}

export interface ScrubbableBreadcrumb {
  category?: string;
  message?: string;
  data?: Record<string, unknown>;
}

/** 스택 프레임의 지역 변수 값(`vars`). 변수명이 뭐든(예: `password`) 통째로 지운다. */
export interface ScrubbableStackFrame {
  filename?: string;
  function?: string;
  vars?: Record<string, unknown>;
}

export interface ScrubbableStacktrace {
  frames?: ScrubbableStackFrame[];
}

export interface ScrubbableException {
  type?: string;
  stacktrace?: ScrubbableStacktrace;
}

export interface ScrubbableThread {
  id?: number | string;
  stacktrace?: ScrubbableStacktrace;
}

export interface ScrubbableEvent {
  request?: ScrubbableRequestData;
  breadcrumbs?: ScrubbableBreadcrumb[];
  extra?: Record<string, unknown>;
  contexts?: Record<string, unknown>;
  user?: Record<string, unknown>;
  exception?: { values?: ScrubbableException[] };
  threads?: { values?: ScrubbableThread[] };
}

/** 이벤트 전체(요청, breadcrumbs, extra, contexts, user)를 스크러빙한다. */
export function scrubEvent(event: ScrubbableEvent): ScrubbableEvent {
  const clone = structuredClone(event);

  if (clone.request) {
    clone.request = scrubRequestData(clone.request);
  }
  if (clone.breadcrumbs) {
    clone.breadcrumbs = clone.breadcrumbs.map((breadcrumb) =>
      scrubBreadcrumb(breadcrumb),
    );
  }
  if (clone.extra) {
    clone.extra = deepScrub(clone.extra) as Record<string, unknown>;
  }
  if (clone.contexts) {
    clone.contexts = deepScrub(clone.contexts) as Record<string, unknown>;
  }
  if ("user" in clone) {
    // ip_address, email, username, id 등 뭐가 들어있든 통째로 지운다.
    delete clone.user;
  }
  if (clone.exception?.values) {
    clone.exception = {
      ...clone.exception,
      values: clone.exception.values.map(scrubException),
    };
  }
  if (clone.threads?.values) {
    clone.threads = {
      ...clone.threads,
      values: clone.threads.values.map(scrubThread),
    };
  }

  return clone;
}

/** breadcrumb 하나(주로 fetch/xhr)를 스크러빙한다. */
export function scrubBreadcrumb(
  breadcrumb: ScrubbableBreadcrumb,
): ScrubbableBreadcrumb {
  const clone = structuredClone(breadcrumb);

  if (clone.data) {
    const data = deepScrub(clone.data) as Record<string, unknown>;
    if (typeof data.url === "string") {
      data.url = stripUrlQueryParams(data.url);
    }
    if (typeof data["url.query"] === "string") {
      data["url.query"] = stripBareQueryString(data["url.query"] as string);
    }
    clone.data = data;
  }

  return clone;
}

/**
 * 예외 하나(`event.exception.values[]`)의 스택트레이스 프레임에서 지역
 * 변수 값을 지운다. 로그인 함수의 지역 변수 중 `password`가 그대로 값으로
 * 잡히는 경우가 대표적이다(변수명은 번들러가 바꿔버릴 수 있어 이름으로
 * 걸러내지 않고 `vars` 자체를 통째로 지운다).
 */
function scrubException(exception: ScrubbableException): ScrubbableException {
  if (!exception.stacktrace) {
    return exception;
  }
  return { ...exception, stacktrace: scrubStacktrace(exception.stacktrace) };
}

/** thread 하나(`event.threads.values[]`)의 스택트레이스도 같은 방식으로 지운다. */
function scrubThread(thread: ScrubbableThread): ScrubbableThread {
  if (!thread.stacktrace) {
    return thread;
  }
  return { ...thread, stacktrace: scrubStacktrace(thread.stacktrace) };
}

function scrubStacktrace(
  stacktrace: ScrubbableStacktrace,
): ScrubbableStacktrace {
  if (!stacktrace.frames) {
    return stacktrace;
  }
  return { ...stacktrace, frames: stacktrace.frames.map(scrubStackFrame) };
}

function scrubStackFrame(frame: ScrubbableStackFrame): ScrubbableStackFrame {
  if (!("vars" in frame)) {
    return frame;
  }
  const result = { ...frame };
  delete result.vars;
  return result;
}

function scrubRequestData(
  request: ScrubbableRequestData,
): ScrubbableRequestData {
  const result: ScrubbableRequestData = { ...request };

  if (result.headers) {
    result.headers = scrubHeaders(result.headers);
  }
  if ("cookies" in result) {
    delete result.cookies;
  }
  if (typeof result.url === "string") {
    result.url = stripUrlQueryParams(result.url);
  }
  if (result.query_string !== undefined) {
    result.query_string = scrubQueryString(result.query_string);
  }
  if (result.data !== undefined) {
    result.data = deepScrub(result.data);
  }
  if ("env" in result) {
    // 감사되지 않은 서버 환경값 통과 필드. REMOTE_ADDR 등 IP가 들어올 수 있다.
    delete result.env;
  }

  return result;
}

function scrubHeaders(headers: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(headers)) {
    result[key] = SENSITIVE_KEYS.has(key.toLowerCase()) ? REDACTED : value;
  }
  return result;
}

function scrubQueryString(query: ScrubbableQueryString): ScrubbableQueryString {
  if (typeof query === "string") {
    return stripBareQueryString(query);
  }
  if (Array.isArray(query)) {
    return query.filter(
      ([key]) => !STRIPPED_QUERY_PARAMS.includes(key.toLowerCase()),
    );
  }
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(query)) {
    if (!STRIPPED_QUERY_PARAMS.includes(key.toLowerCase())) {
      result[key] = value;
    }
  }
  return result;
}

/** 절대/상대 URL 문자열에서 `code`, `state` 쿼리 파라미터를 지운다. */
function stripUrlQueryParams(url: string): string {
  const isAbsolute = /^[a-z][a-z0-9+.-]*:\/\//i.test(url);
  let parsed: URL;
  try {
    parsed = new URL(url, isAbsolute ? undefined : "http://scrub.invalid");
  } catch {
    return url;
  }

  let changed = false;
  for (const param of STRIPPED_QUERY_PARAMS) {
    if (parsed.searchParams.has(param)) {
      parsed.searchParams.delete(param);
      changed = true;
    }
  }
  if (!changed) {
    return url;
  }

  return isAbsolute
    ? parsed.toString()
    : `${parsed.pathname}${parsed.search}${parsed.hash}`;
}

/** `key=value&key2=value2` 형태(맨 앞 `?` 있어도 됨)의 순수 쿼리 문자열을 다룬다. */
function stripBareQueryString(query: string): string {
  const hasLeadingQuestion = query.startsWith("?");
  const params = new URLSearchParams(
    hasLeadingQuestion ? query.slice(1) : query,
  );

  let changed = false;
  for (const param of STRIPPED_QUERY_PARAMS) {
    if (params.has(param)) {
      params.delete(param);
      changed = true;
    }
  }
  if (!changed) {
    return query;
  }

  const result = params.toString();
  return hasLeadingQuestion ? `?${result}` : result;
}

/**
 * 객체/배열/JSON 문자열을 재귀적으로 훑으며 {@link SENSITIVE_KEYS}에 해당하는
 * 키의 값을 지운다. 문자열 값이 JSON으로 파싱되면 그 안까지 들어가 다시
 * 문자열로 되돌린다(요청 본문이 문자열로 온 경우를 위해).
 */
function deepScrub(value: unknown): unknown {
  if (typeof value === "string") {
    return scrubJsonString(value);
  }
  if (Array.isArray(value)) {
    return value.map((item) => deepScrub(item));
  }
  if (value !== null && typeof value === "object") {
    const result: Record<string, unknown> = {};
    for (const [key, val] of Object.entries(value as Record<string, unknown>)) {
      result[key] = SENSITIVE_KEYS.has(key.toLowerCase())
        ? REDACTED
        : deepScrub(val);
    }
    return result;
  }
  return value;
}

function scrubJsonString(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length === 0 || (trimmed[0] !== "{" && trimmed[0] !== "[")) {
    return value;
  }
  try {
    const parsed = JSON.parse(trimmed);
    return JSON.stringify(deepScrub(parsed));
  } catch {
    return value;
  }
}
