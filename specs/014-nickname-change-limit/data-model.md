# Data Model: 닉네임 변경 제한 (014-nickname-change-limit)

마이그레이션은 `V14__nickname_change_limit.sql` 하나다. 올리기 직전에 `origin/main`의 번호를 다시 본다.

## `member`

| 열 | 형 | 설명 |
|---|---|---|
| `nickname_locked_until` | `TIMESTAMPTZ` NULL | 닉네임을 다시 바꿀 수 있게 되는 때. NULL이거나 지났으면 바꿀 수 있다 |

- 닉네임을 바꿀 때 `지금 + ogu.member.nickname-change.interval`(기본 30일)을 적는다. 온보딩은 적지 않는다.
- `member_withdrawn_erased_check`에 `nickname_locked_until IS NULL`을 더한다. 탈퇴한 행에는 번호와 탈퇴한 때만 남는다(013).

## 상태

```text
제한 없음(NULL 또는 지난 때) ──닉네임 변경──▶ 제한 중(지금 < nickname_locked_until)
        ▲                                              │
        └────────────── 시간이 지남 ───────────────────┘
```

제한 중에는 닉네임 변경이 `409 NICKNAME_CHANGE_LIMITED`다. 직군과 경력, 지금과 같은 닉네임은 그대로 받는다.

## 공개 타입

| 어디 | 더하는 것 |
|---|---|
| 계약 `MemberProfile` | `nicknameChangeAvailableAt: date-time \| null` |
| `shared` | `ErrorCode.NICKNAME_CHANGE_LIMITED`(409) |

`MemberInfo`(다른 모듈이 읽는 회원)에는 더하지 않는다. 다른 회원의 제한은 어디에도 나가지 않는다(FR-008).
