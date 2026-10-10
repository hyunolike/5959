-- 008-weekly-report 성능 측정용 시드(T024, SC-004, quickstart "성능 측정").
-- 회원 1천 명을 만들고 저마다 지난주(한국 시간)에 글 셋을 쓴 것으로 넣는다. 감정 분석 결과와 서로 남긴 공감도 넣는다.
-- 끝에서 지난주의 리포트 만들기 기록을 지워, 다음 주기에 이 회원들의 리포트가 만들어지게 한다.
--
--   docker exec -i <postgres 컨테이너> psql -U ogu -d ogu -v ON_ERROR_STOP=1 < apps/api/src/test/resources/seed/m7-weekly-report-perf.sql
--
-- 테스트 DB 전용이다. 운영 DB에 돌리지 않는다.

BEGIN;

-- 닉네임이 겹치지 않게 돌릴 때마다 다른 다섯 자리를 붙인다
CREATE TEMP TABLE seed_members ON COMMIT DROP AS
WITH tag AS (SELECT lpad(((extract(epoch FROM now())::bigint) % 100000)::text, 5, '0') AS t),
inserted AS (
  INSERT INTO member (auth_method, nickname, nickname_key, job_role, career_year, onboarded_at, created_at, updated_at)
  SELECT 'KAKAO', 'w' || tag.t || n, 'w' || tag.t || n, 'DEVELOPMENT', 'YEAR_3', now(), now(), now()
  FROM generate_series(1000, 1999) n, tag
  RETURNING id
)
SELECT id, row_number() OVER (ORDER BY id) AS n FROM inserted;

-- 지난주 월요일 0시(한국 시간)
CREATE TEMP TABLE seed_week ON COMMIT DROP AS
SELECT ((date_trunc('week', now() AT TIME ZONE 'Asia/Seoul') - interval '7 days') AT TIME ZONE 'Asia/Seoul') AS start_at;

CREATE TEMP TABLE seed_posts ON COMMIT DROP AS
WITH inserted AS (
  INSERT INTO posts (author_id, author_job_role, author_career_year, content, comment_tone, created_at, updated_at)
  SELECT m.id, 'DEVELOPMENT', 'YEAR_3', '주간 리포트 성능 측정용 글 ' || k, 'COMFORT_ME',
         w.start_at + (interval '1 day' * k) + (interval '1 second' * m.n),
         w.start_at + (interval '1 day' * k) + (interval '1 second' * m.n)
  FROM seed_members m, generate_series(1, 3) k, seed_week w
  RETURNING id, author_id, created_at
)
SELECT i.id, i.author_id, i.created_at, m.n FROM inserted i JOIN seed_members m ON m.id = i.author_id;

INSERT INTO emotion_analysis (post_id, status, emotion, intensity, next_attempt_at, post_created_at, completed_at)
SELECT p.id, 'ANALYZED',
       (ARRAY['ANXIETY', 'LETHARGY', 'LONELINESS', 'SELF_DEPRECATION', 'IRRITATION'])[1 + (p.id % 5)],
       'LOW', p.created_at, p.created_at, p.created_at
FROM seed_posts p;

-- 다음 번호의 회원이 공감한다(받은 공감 수)
INSERT INTO post_likes (post_id, member_id, created_at)
SELECT p.id, liker.id, p.created_at + interval '1 hour'
FROM seed_posts p
JOIN seed_members liker ON liker.n = (p.n % 1000) + 1;

UPDATE posts p SET like_count = 1 FROM seed_posts s WHERE s.id = p.id;

DELETE FROM weekly_report_run;

SELECT min(id) AS first_member, max(id) AS last_member, count(*) AS members FROM seed_members;

COMMIT;
