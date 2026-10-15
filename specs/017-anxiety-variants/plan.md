# Implementation Plan: 여러 모습의 불안 몬스터

**Branch**: `017-anxiety-variants` | **Date**: 2026-10-15 | **Spec**: [spec.md](spec.md)

## Summary

불안 몬스터에 세 모습(젤리, 구름, 시계 토끼)을 더해 네 모습으로 만든다. 모습은 글 번호를 4로 나눈 나머지로 정하고(0이면 지금의 털뭉치), 그림 경로만 바뀐다. API, 계약, DB는 그대로다. 그림은 Codex CLI로 지금 그림을 참고로 넘겨 그리고(ADR-0006), 흰 배경을 지워 512×512 WebP로 넣는다.

## Technical Context

**Language/Version**: TypeScript 5 strict (웹만)

**Primary Dependencies**: Next.js 16, React 19. 새 의존 없음

**Storage**: 없음. 그림 12장을 `apps/web/public/monsters/anxiety-v{2,3,4}-{full,hurt,weak,defeated}.webp`로 더한다

**Testing**: Vitest + Testing Library, Playwright(`e2e`)

**Constraints**: 대체 텍스트("불안 몬스터, 멀쩡함")와 그림 자리가 그대로라 기존 e2e 선택자가 깨지지 않아야 한다. 그림 한 장은 72KB를 넘지 않는다(SC-002)

**Scale/Scope**: 인수 조건 6개. `entities/monster`의 경로 함수와 네 컴포넌트, 글 카드 두 개, 호출하는 위젯 다섯 곳

## Constitution Check

| 원칙 | 판정 |
|---|---|
| I. 경계 | `entities/monster`가 모습을 정하고 위젯이 글 번호를 넘긴다. 아래 레이어만 부른다. 통과 |
| II. 계약 | API를 바꾸지 않는다. 통과 |
| III. 인수 조건 | 6개 모두 단위 테스트로 검증한다. 통과 |
| IV. 안전 | 관계없음. 통과 |
| V. AI 장애 | 관계없음. 통과 |
| VI. 무료 인프라 | 정적 그림만 는다. 통과 |

## 설계 (research)

- **R1 모습은 글 번호의 나머지**: `monsterLook(emotion, postId)`는 불안이면 `postId % 4`, 아니면 0. 저장하지 않아 모든 화면이 같은 답을 낸다. 해시를 쓰지 않은 까닭은 연속한 글 넷이 서로 다른 모습이 되게 하려는 것이다(SC-001).
- **R2 경로**: `spritePath(emotion, stage, boss, look)`는 `look > 0`인 불안이면 `/monsters/anxiety-v{look+1}-{stage}.webp`. 보스는 `look`을 무시한다.
- **R3 전달**: `MonsterSprite`, `MonsterThumb`, `MonsterSummary`, `MonsterDisplay`가 선택 프롭 `postId`를 받는다. `PostCard`와 `PublicPostCard`의 `renderMonster`가 두 번째 인자로 글 번호를 넘긴다. `MonsterCard`는 `card.postId`를 쓴다. 넘기지 않으면 지금 그림이다(감정 소개, 보스, 사용법 장면).
- **R4 그림**: 모습마다 멀쩡함을 먼저 그리고, 그 그림을 참고로 상처 입음(반창고, 한쪽 눈 감김), 약해짐(소용돌이 눈, 별, 반창고 둘), 쓰러짐(납작하게 엎드림, X 눈, 별 셋)을 그린다. 지금의 단계 그림을 함께 참고로 넘겨 단계 표현을 맞춘다. 흰 배경은 테두리에서 이어진 흰색만 지운다.
