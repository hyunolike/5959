# Data Model: 몬스터 도감 (010-monster-cards)

새 테이블과 마이그레이션은 없다. 이미 있는 `monsters`와 `monster_hp_log`에서 읽는다(research R1).

## 읽는 것

| 테이블 | 열 | 쓰임 |
|---|---|---|
| `monsters` | `id`, `post_id`, `emotion`, `max_hp`, `status`, `defeated_at` | 카드 번호, 글, 감정, 등급, 처치 여부와 때 |
| `monster_hp_log` | `monster_id`, `member_id`, `hp_before`, `hp_after` | 내가 줄인 HP, HP를 줄인 회원 수 |
| `posts` | `id`, `author_id`, `deleted_at`, `hidden_at` | 내 글인지, 보이는 글인지(`post`의 파사드로) |

카드를 고르는 쿼리는 `monster_hp_log`를 회원으로 찾는다. 구현할 때 실행 계획을 보고, 유일 키(`monster_id, member_id, action, target_id`)로 모자라면 `CREATE INDEX monster_hp_log_member_idx ON monster_hp_log (member_id, monster_id)`를 마이그레이션으로 더한다. 마이페이지 통계의 `defeatedPostIds`가 같은 길을 이미 쓰고 있다.

## monster 모듈 (더하는 것)

`MonsterApi.cardRows(memberId, ownPostIds): List<MonsterCardRow>`

| `MonsterCardRow` | 타입 | 설명 |
|---|---|---|
| `monsterId` | Long | |
| `postId` | Long | |
| `emotion` | EmotionType | |
| `maxHp` | Int | 10, 20, 30 |
| `defeatedAt` | Instant | |
| `damageBy` | Int | `memberId`가 줄인 HP의 합(`hp_before - hp_after`) |
| `damagerCount` | Int | HP를 줄인 회원 수 |

처치된(`status = 'DEFEATED'`) 몬스터 가운데 `post_id`가 `ownPostIds`에 있거나 `memberId`의 기록 가운데 `hp_after < hp_before`인 것이 있는 몬스터다. 쿼리 한 번이다.

## post 모듈 (고치는 것)

`PostSelectionApi.visibleIds(postIds, exceptAuthorId: Long? = null)`. 작성자를 빼는 인자를 없어도 되게 한다. 주면 지금과 같다.

## feed 모듈 (더하는 것)

`MonsterCardQuery.page(memberId, cursor, size, emotion): MonsterCardPageResponse`

| 값 | 규칙 |
|---|---|
| `role` | 글 번호가 내 글이면 `MINE`, 아니면 `TOGETHER` |
| `grade` | `maxHp` 10 → `COMMON`, 20 → `RARE`, 30 → `LEGENDARY` |
| `myDamage` | `damageBy`. `MINE`이면 0 |
| `helperCount` | `MINE`이면 `damagerCount`, `TOGETHER`이면 null |
| 정렬 | `defeatedAt DESC`, `cardNo DESC` |
| 커서 | `base64url("{defeatedAt의 마이크로초}:{cardNo}")` |
| `summary.total` | 보이는 카드 전체의 수(감정으로 거르기 전) |
| `summary.byEmotion` | 다섯 감정이 정해진 순서로 모두 있고 없는 감정은 0 |

## 웹

| 값 | 표시 |
|---|---|
| `COMMON`, `RARE`, `LEGENDARY` | 일반, 희귀, 전설 |
| `MINE`, `TOGETHER` | 내 몬스터, 함께 물리침 |
| 그림 | `public/monsters/{감정}-defeated.webp`(지금 있는 것) |
| `defeatedAt` | 목록은 "10월 12일", 크게 볼 때는 "2026년 10월 12일에 물리침" |

## 이벤트와 설정

없다.
