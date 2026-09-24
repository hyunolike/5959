# ADR-0002 BFF 인증

- 상태: 채택
- 날짜: 2026-09-24

## 맥락

`nextjs-fsd-template`은 access 토큰을 localStorage에, refresh 토큰을 httpOnly 쿠키에 둔다.
localStorage의 토큰은 XSS가 한 번만 성공해도 탈취된다.
원본(`webbb-fe`)은 두 토큰을 모두 httpOnly 쿠키에 두고 Next.js 라우트 핸들러가 백엔드로 중계했다.

## 결정

BFF 패턴을 쓴다.

- 두 토큰을 모두 httpOnly, Secure, SameSite=Lax 쿠키에 둔다.
- 브라우저는 같은 출처의 `/api/*`만 호출하고, 라우트 핸들러가 쿠키를 `Authorization: Bearer`로 바꿔 전달한다.
- 401이면 refresh를 한 번 시도하고 원래 요청을 다시 보낸다. 동시에 들어온 401은 refresh 한 번으로 묶는다.
- SSE는 BFF를 거치지 않는다. BFF가 30초짜리 일회용 티켓을 발급하고, 브라우저가 티켓으로 API 도메인에 직접 연결한다.

## 검토한 대안

- **템플릿 방식(localStorage)**: 구현은 가장 단순하지만 XSS에 취약하다.
- **SSE도 BFF로 중계**: Vercel 함수는 실행 시간 제한이 있어서 긴 연결을 유지할 수 없다.

## 결과

- 토큰이 브라우저 스크립트에 노출되지 않는다.
- 모든 API 호출이 Vercel 함수를 한 번 더 거쳐서 지연이 조금 늘어난다. Vercel 리전을 서울(icn1)로 두어 줄인다.
- SSE 티켓 발급과 검증 로직이 추가로 필요하다.
