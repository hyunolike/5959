/**
 * 로그인 뒤 돌아갈 `next`를 같은 출처 경로로만 제한한다(bff-routes.md 라우트
 * 가드, 스펙 경계 상황). 서버(라우트 가드, OAuth 라우트)와 브라우저(로그인,
 * 가입, 온보딩 폼)가 같은 규칙을 쓰도록 순수 함수로 둔다.
 *
 * - `/`로 시작하고 `//`나 `/\`로 시작하지 않는다(스킴이 붙은 값은 `/`로 시작할 수 없다).
 * - 브라우저가 URL에서 지우는 탭, 줄바꿈 같은 제어 문자를 막는다
 *   (`/\t/evil`이 `//evil`이 되는 것을 막는다).
 * - 한 번 퍼센트 디코딩한 값에도 같은 규칙을 적용한다(`/%2F%2Fevil`, `/%5Cevil`).
 *   중간 단계가 경로를 디코딩하면 프로토콜 상대 주소가 될 수 있어서다.
 * - 어떤 기준 출처에 붙여도 그 출처를 벗어나지 않아야 한다.
 *
 * 규칙에 맞지 않으면 `/home`이다.
 */
export const DEFAULT_NEXT_PATH = "/home";

const MAX_NEXT_PATH_LENGTH = 512;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/;

function hasSafeShape(path: string): boolean {
  return (
    path.startsWith("/") &&
    !path.startsWith("//") &&
    !path.startsWith("/\\") &&
    !CONTROL_CHARACTERS.test(path)
  );
}

export function sanitizeNextPath(next: string | null | undefined): string {
  if (
    typeof next !== "string" ||
    next.length > MAX_NEXT_PATH_LENGTH ||
    !hasSafeShape(next)
  ) {
    return DEFAULT_NEXT_PATH;
  }
  let decoded: string;
  try {
    decoded = decodeURIComponent(next);
  } catch {
    return DEFAULT_NEXT_PATH;
  }
  if (!hasSafeShape(decoded)) {
    return DEFAULT_NEXT_PATH;
  }
  const base = "http://ogu.invalid";
  try {
    if (new URL(next, base).origin !== base) {
      return DEFAULT_NEXT_PATH;
    }
  } catch {
    return DEFAULT_NEXT_PATH;
  }
  return next;
}

/**
 * `path`에 검증한 `next`를 쿼리로 붙인다. `next`가 기본값(`/home`)이거나 검증을
 * 통과하지 못하면 붙이지 않는다. 로그인 오류 화면, 온보딩 화면처럼 한 단계를
 * 더 거친 뒤에도 원래 가려던 곳으로 돌아가기 위해 쓴다.
 */
export function withNextPath(
  path: string,
  next: string | null | undefined,
): string {
  const safeNext = sanitizeNextPath(next);
  if (safeNext === DEFAULT_NEXT_PATH) {
    return path;
  }
  const separator = path.includes("?") ? "&" : "?";
  return `${path}${separator}next=${encodeURIComponent(safeNext)}`;
}
