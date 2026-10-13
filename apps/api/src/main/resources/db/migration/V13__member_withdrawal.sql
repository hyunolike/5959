-- 013-member-withdrawal: 회원 탈퇴 (specs/013-member-withdrawal/data-model.md)

-- member: 탈퇴한 때. 행은 남기고 개인 정보 열을 비운다(research R1). 공감, HP 기록, 레이드 기여가 이 번호를 가리킨다
ALTER TABLE member
  ADD COLUMN withdrawn_at TIMESTAMPTZ;

-- 이메일 가입의 "이메일과 비밀번호가 있어야 한다"에서 탈퇴한 행을 뺀다. auth_method는 가입한 방법의 기록으로 남는다
ALTER TABLE member DROP CONSTRAINT member_email_password_required_for_email_check;
ALTER TABLE member
  ADD CONSTRAINT member_email_password_required_for_email_check
    CHECK (withdrawn_at IS NOT NULL OR auth_method <> 'EMAIL' OR (email IS NOT NULL AND password_hash IS NOT NULL)),
  -- 탈퇴한 행에는 개인 정보가 남지 않는다. AI 친구와 운영자는 탈퇴한 행이 될 수 없다(research R6)
  ADD CONSTRAINT member_withdrawn_erased_check
    CHECK (
      withdrawn_at IS NULL
        OR (email IS NULL AND password_hash IS NULL AND nickname IS NULL AND nickname_key IS NULL
          AND job_role IS NULL AND career_year IS NULL AND onboarded_at IS NULL
          AND kind = 'HUMAN' AND role = 'MEMBER')
    );

-- 탈퇴한 회원의 댓글을 지운 것까지 번호 순으로 맡는다(research R4). comments_author_live_idx는 살아 있는 댓글만 담는다
CREATE INDEX comments_author_id_idx ON comments (author_id, id);
