# Changelog

## 1.0.0 (2026-10-15)


### Features

* **infra:** add low-cost GCP deployment with Google login ([#41](https://github.com/hyunolike/5959/issues/41)) ([1a3e8b5](https://github.com/hyunolike/5959/commit/1a3e8b5654eae41fcab687d9374c109e8a05a70b))
* 닉네임을 바꾸면 30일 동안 다시 바꿀 수 없다 (014) ([#84](https://github.com/hyunolike/5959/issues/84)) ([407443b](https://github.com/hyunolike/5959/commit/407443b6f24bb6cb1392063d6ad68d90d1849f74))
* 둘러보기에서 최근 이야기를 닉네임 없이 보인다 ([#71](https://github.com/hyunolike/5959/issues/71)) ([7448324](https://github.com/hyunolike/5959/commit/7448324cf05e03e2d54ac4ff0c0dfa0a6a4f0668))
* 물리친 몬스터를 카드로 모아 보는 몬스터 도감을 더한다 ([#66](https://github.com/hyunolike/5959/issues/66)) ([1cad848](https://github.com/hyunolike/5959/commit/1cad8488c129257921a766caa058600f03fa91cd))
* 반응 없는 글에 AI 친구가 댓글과 공감을 남긴다 ([#67](https://github.com/hyunolike/5959/issues/67)) ([466f98e](https://github.com/hyunolike/5959/commit/466f98e0a01b5f75405e57c5195d7b04418bb3c9))
* 반응 없는 글에 AI 친구가 한 명에서 세 명까지 답한다 (012 1/2) ([#79](https://github.com/hyunolike/5959/issues/79)) ([77e6879](https://github.com/hyunolike/5959/commit/77e687975dbfabb92e19226a9542aeb0df949e63))
* 보스 레이드 (M5, 006-raid) ([#24](https://github.com/hyunolike/5959/issues/24)) ([c084259](https://github.com/hyunolike/5959/commit/c0842593282c0c36631e63364bf011c118933a64))
* 비슷한 고민 추천 (M6, 007-recommend) ([#26](https://github.com/hyunolike/5959/issues/26)) ([7c96a30](https://github.com/hyunolike/5959/commit/7c96a30f0ca7d9a2aea550d3c86f7b9f3e6a7dc3))
* 알림과 마이페이지 (M3, 004-notification-mypage) ([#22](https://github.com/hyunolike/5959/issues/22)) ([20d3fb4](https://github.com/hyunolike/5959/commit/20d3fb47ccb1385c6c4c6a33c6db7495e6627599))
* 위험 감지와 안전장치 (M4, 005-safety) ([#23](https://github.com/hyunolike/5959/issues/23)) ([5a650ea](https://github.com/hyunolike/5959/commit/5a650ea39bb8ee3095cb20d6d76c7ea70cdd15cf))
* 이메일 가입 회원이 메일의 링크로 비밀번호를 재설정한다 (015) ([#85](https://github.com/hyunolike/5959/issues/85)) ([93dd29e](https://github.com/hyunolike/5959/commit/93dd29e139ea6360dcbfffd65826bceec974749f))
* 인증과 회원 (M1, 002-auth) ([#14](https://github.com/hyunolike/5959/issues/14)) ([822add9](https://github.com/hyunolike/5959/commit/822add9a85073fc45b373c3a8aab3777a8e063e4))
* 조용할 때 AI 친구가 고민 글을 쓴다 (012 2/2) ([#80](https://github.com/hyunolike/5959/issues/80)) ([5c0cb2e](https://github.com/hyunolike/5959/commit/5c0cb2e6dbbf45168d59ef1c13838364a4a897cf))
* 주간 리포트 (M7, 008-weekly-report) ([#27](https://github.com/hyunolike/5959/issues/27)) ([a9505dc](https://github.com/hyunolike/5959/commit/a9505dc907d875fc0a88f904b8f8b71c0e34572a))
* 핵심 루프 (M2, 003-core-loop) ([#20](https://github.com/hyunolike/5959/issues/20)) ([fa1e709](https://github.com/hyunolike/5959/commit/fa1e70903803ada5dd10e0a335ac2d34b3be29dc))
* 회원이 스스로 탈퇴한다 (013) ([#82](https://github.com/hyunolike/5959/issues/82)) ([f22a3d0](https://github.com/hyunolike/5959/commit/f22a3d089234c2868b564cffd2cbc30ed6b5fbd1))


### Bug Fixes

* **api:** 위험 분류에 더 큰 모델을 따로 써서 에두른 위기 표현을 더 잡는다 ([#31](https://github.com/hyunolike/5959/issues/31)) ([e32b53b](https://github.com/hyunolike/5959/commit/e32b53b72a2a5f32c97efd02e3e1e4041bc20f2b))
* **api:** 초성 욕설과 흔한 변형을 가리고, 띄어 쓴 두 낱말이 만난 것은 가리지 않는다 ([#78](https://github.com/hyunolike/5959/issues/78)) ([5347661](https://github.com/hyunolike/5959/commit/534766137faf13340d110149d5451128f7eed6d6))
* **notification:** 스트림의 첫 쓰기를 요청 스레드에서 내보내기 ([#40](https://github.com/hyunolike/5959/issues/40)) ([6604db9](https://github.com/hyunolike/5959/commit/6604db9bfe82379261c0e8adbb5bb2243f237d7f))
* 서비스가 끝난 기본 AI 모델 교체와 AI 포함 위험 감지 평가 ([#25](https://github.com/hyunolike/5959/issues/25)) ([91f9a03](https://github.com/hyunolike/5959/commit/91f9a03d56fe176fe05e8157099268a686b7d89c))
