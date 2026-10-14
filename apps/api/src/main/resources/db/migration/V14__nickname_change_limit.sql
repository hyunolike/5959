-- 014-nickname-change-limit: 닉네임 변경 제한 (specs/014-nickname-change-limit/data-model.md)

-- member: 닉네임을 다시 바꿀 수 있게 되는 때(research R1). NULL이거나 지났으면 바꿀 수 있다.
-- 바꾼 때가 아니라 끝나는 때를 적는다. 기간 설정을 바꿔도 이미 걸린 제한은 그대로다.
-- 이 기능 전에 닉네임을 바꾼 회원은 NULL이라 제한이 없다(FR-002).
ALTER TABLE member
  ADD COLUMN nickname_locked_until TIMESTAMPTZ;

-- 탈퇴한 행에는 번호와 탈퇴한 때만 남는다(013). 닉네임의 제한도 함께 지워진다(FR-011)
ALTER TABLE member DROP CONSTRAINT member_withdrawn_erased_check;
ALTER TABLE member
  ADD CONSTRAINT member_withdrawn_erased_check
    CHECK (
      withdrawn_at IS NULL
        OR (email IS NULL AND password_hash IS NULL AND nickname IS NULL AND nickname_key IS NULL
          AND job_role IS NULL AND career_year IS NULL AND onboarded_at IS NULL
          AND nickname_locked_until IS NULL
          AND kind = 'HUMAN' AND role = 'MEMBER')
    );
