import { BEFORE_SEND_NAME, BEFORE_SEND_SCRIPT } from "./before-send";

/**
 * Umami Cloud에서 사이트를 만들면 받는 웹사이트 ID. 페이지 HTML에 그대로 보이는 공개 값이라
 * 비밀이 아니다. 비어 있으면 스크립트를 그리지 않는다.
 */
export const UMAMI_WEBSITE_ID: string | null =
  "b08a7474-17e7-424c-a1ef-b70bd2361aad";

/** 운영 주소에서만 센다. 로컬, e2e, 미리보기 배포에는 트래커를 싣지 않는다. */
export const UMAMI_DOMAINS = "new.5959.site";

const UMAMI_SRC = "https://cloud.umami.is/script.js";

/**
 * 트래커를 붙이는 스크립트. Umami 트래커는 시작할 때 `localStorage.getItem`을 예외 처리 없이
 * 부르므로, 저장소가 막힌 브라우저에서는 붙이지 않는다(페이지 오류를 남기지 않게).
 */
export function loaderScript(websiteId: string): string {
  const attrs = {
    websiteId,
    domains: UMAMI_DOMAINS,
    beforeSend: BEFORE_SEND_NAME,
  };
  return `${BEFORE_SEND_SCRIPT}(function(){try{window.localStorage.getItem("umami.disabled")}catch(e){return}var s=document.createElement("script");s.defer=true;s.src=${JSON.stringify(UMAMI_SRC)};var a=${JSON.stringify(attrs)};for(var k in a)s.dataset[k]=a[k];document.head.appendChild(s)})();`;
}

/**
 * 방문 분석(Umami). 쿠키를 쓰지 않고 개인을 식별하지 않는다. 보내기 전에 주소의 검색어를 지운다
 * (`before-send.ts`). 서비스 주소가 운영 주소일 때만 그린다.
 */
export function UmamiAnalytics({
  appOrigin,
  websiteId = UMAMI_WEBSITE_ID,
}: {
  appOrigin: string;
  websiteId?: string | null;
}) {
  if (!websiteId || new URL(appOrigin).hostname !== UMAMI_DOMAINS) return null;
  return (
    <script
      id="umami-loader"
      dangerouslySetInnerHTML={{ __html: loaderScript(websiteId) }}
    />
  );
}
