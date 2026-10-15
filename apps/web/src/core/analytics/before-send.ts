/**
 * Umami가 보내기 직전에 부르는 함수(`data-before-send`). 주소와 이전 주소에서 `utm_`으로 시작하는
 * 검색어만 남기고 나머지 검색어와 `#` 뒤를 지운다. 비밀번호 재설정 링크(`?token=`)처럼 주소에 실린
 * 값이 바깥 서비스로 나가지 않게 한다.
 *
 * 문자열로 바꿔 페이지에 그대로 심으므로 바깥의 어떤 것도 참조하지 않는다.
 */
export function umamiBeforeSend(
  type: string,
  payload: Record<string, unknown>,
): Record<string, unknown> {
  function clean(value: unknown): unknown {
    if (typeof value !== "string" || value === "") return value;
    const absolute = /^[a-z][a-z0-9+.-]*:\/\//i.test(value);
    let url: URL;
    try {
      url = new URL(value, "https://placeholder.invalid");
    } catch {
      return value.split(/[?#]/)[0];
    }
    const kept = new URLSearchParams();
    url.searchParams.forEach((v, k) => {
      if (k.toLowerCase().startsWith("utm_")) kept.append(k, v);
    });
    const search = kept.toString();
    const rest = url.pathname + (search ? `?${search}` : "");
    return absolute ? url.origin + rest : rest;
  }
  return {
    ...payload,
    url: clean(payload.url),
    referrer: clean(payload.referrer),
  };
}

/** 페이지에 심을 때 쓰는 이름. `window`에 이 이름으로 함수를 둔다. */
export const BEFORE_SEND_NAME = "__oguUmamiBeforeSend";

/** 트래커보다 먼저 실행할 스크립트 본문. */
export const BEFORE_SEND_SCRIPT = `window.${BEFORE_SEND_NAME}=${umamiBeforeSend.toString()};`;
