// Vitest 대역. 실제 `server-only` 패키지는 클라이언트 번들에 포함되면 항상 throw하는데,
// Next.js는 서버 번들을 만들 때 이 모듈을 빈 모듈로 alias한다. Vitest는 그런 번들 구분이
// 없어서 같은 alias를 vitest.config.ts에서 재현한다.
export {};
