import Script from "next/script";

import { BEFORE_SEND_NAME, BEFORE_SEND_SCRIPT } from "./before-send";

/**
 * Umami Cloud에서 사이트를 만들면 받는 웹사이트 ID. 페이지 HTML에 그대로 보이는 공개 값이라
 * 비밀이 아니다. 비어 있으면 스크립트를 그리지 않는다.
 */
export const UMAMI_WEBSITE_ID: string | null =
  "b08a7474-17e7-424c-a1ef-b70bd2361aad";

/** 운영 주소에서만 센다. 로컬, e2e, 미리보기 배포의 방문은 보내지 않는다. */
export const UMAMI_DOMAINS = "new.5959.site";

const UMAMI_SRC = "https://cloud.umami.is/script.js";

/**
 * 방문 분석(Umami). 쿠키를 쓰지 않고 개인을 식별하지 않는다. 보내기 전에 주소의 검색어를 지운다
 * (`before-send.ts`).
 */
export function UmamiAnalytics({
  websiteId = UMAMI_WEBSITE_ID,
}: {
  websiteId?: string | null;
}) {
  if (!websiteId) return null;
  return (
    <>
      {/* HTML에 그대로 실려 먼저 실행된다. 트래커는 화면이 뜬 뒤(afterInteractive)에 불러온다. */}
      <script
        id="umami-before-send"
        dangerouslySetInnerHTML={{ __html: BEFORE_SEND_SCRIPT }}
      />
      <Script
        id="umami"
        src={UMAMI_SRC}
        strategy="afterInteractive"
        data-website-id={websiteId}
        data-domains={UMAMI_DOMAINS}
        data-before-send={BEFORE_SEND_NAME}
      />
    </>
  );
}
