-- 004-notification-mypage 성능 측정용 시드(T068, SC-004, quickstart 22번).
-- 회원 하나(:member_id)에 글 1천 개와 알림 1만 개를 넣고, 마이페이지 목록과 감정 통계가 읽을 데이터를 함께 만든다.
-- :actor_id는 알림의 행동한 회원이자 :member_id가 댓글과 공감을 남길 글의 주인이다. 두 회원은 가입과 온보딩을 마친
-- 상태여야 하고, :member_id는 이 스크립트를 돌리기 전에 받은 알림이 없어야 한다(번호를 1부터 매긴다).
--
--   psql "$DB_URL" -v member_id=101 -v actor_id=102 -f apps/api/src/test/resources/seed/m3-perf.sql
--
-- 테스트 DB 전용이다. 운영 DB에 돌리지 않는다.

BEGIN;

-- 1. 내 글 1천 개. 작성 시각을 지난 8주에 고르게 흩뿌린다(최근 것일수록 ID가 크도록 오래된 것부터 넣는다).
CREATE TEMP TABLE seed_my_posts ON COMMIT DROP AS
WITH inserted AS (
  INSERT INTO posts (author_id, author_job_role, author_career_year, content, comment_tone, like_count,
                     comment_count, created_at, updated_at)
  SELECT m.id, m.job_role, m.career_year,
         '성능 측정용 고민 글 ' || n || '. 요즘 회사에서 있었던 일을 길게 적어 본다. 미리보기가 잘리는지도 함께 본다.',
         'COMFORT_ME', n % 7, n % 5,
         now() - (interval '56 days' * (1000 - n) / 1000.0),
         now() - (interval '56 days' * (1000 - n) / 1000.0)
  FROM generate_series(1, 1000) AS n
       JOIN member m ON m.id = :member_id
  ORDER BY n
  RETURNING id, created_at
)
SELECT id, created_at, row_number() OVER (ORDER BY id) AS n FROM inserted;

INSERT INTO emotion_analysis (post_id, status, emotion, intensity, reason, attempts, next_attempt_at,
                              post_created_at, completed_at)
SELECT id, 'ANALYZED',
       (ARRAY['ANXIETY', 'LETHARGY', 'LONELINESS', 'SELF_DEPRECATION', 'IRRITATION'])[1 + n % 5],
       'LOW', '시드', 1, created_at, created_at, created_at
FROM seed_my_posts;

-- 다섯에 하나는 처치된 몬스터다
INSERT INTO monsters (post_id, emotion, max_hp, hp, status, defeated_at, created_at)
SELECT id,
       (ARRAY['ANXIETY', 'LETHARGY', 'LONELINESS', 'SELF_DEPRECATION', 'IRRITATION'])[1 + n % 5],
       10,
       CASE WHEN n % 5 = 0 THEN 0 ELSE 10 END,
       CASE WHEN n % 5 = 0 THEN 'DEFEATED' ELSE 'ALIVE' END,
       CASE WHEN n % 5 = 0 THEN created_at ELSE NULL END,
       created_at
FROM seed_my_posts;

-- 2. 다른 회원의 글 1천 개와, 거기에 내가 남긴 댓글 1천 개, 공감 1천 개
CREATE TEMP TABLE seed_other_posts ON COMMIT DROP AS
WITH inserted AS (
  INSERT INTO posts (author_id, author_job_role, author_career_year, content, comment_tone, like_count,
                     comment_count, created_at, updated_at)
  SELECT m.id, m.job_role, m.career_year, '성능 측정용 남의 글 ' || n, 'WARM_ADVICE', 1, 1,
         now() - (interval '56 days' * (1000 - n) / 1000.0),
         now() - (interval '56 days' * (1000 - n) / 1000.0)
  FROM generate_series(1, 1000) AS n
       JOIN member m ON m.id = :actor_id
  ORDER BY n
  RETURNING id, created_at
)
SELECT id, created_at, row_number() OVER (ORDER BY id) AS n FROM inserted;

INSERT INTO comments (post_id, author_id, content, created_at, updated_at)
SELECT id, :member_id, '성능 측정용 댓글 ' || n, created_at + interval '1 minute', created_at + interval '1 minute'
FROM seed_other_posts
ORDER BY n;

INSERT INTO post_likes (post_id, member_id, created_at)
SELECT id, :member_id, created_at + interval '2 minutes'
FROM seed_other_posts;

-- 남의 글 가운데 100개는 내가 HP를 줄여 처치한 몬스터다(함께 물리친 몬스터)
WITH spawned AS (
  INSERT INTO monsters (post_id, emotion, max_hp, hp, status, defeated_at, created_at)
  SELECT id, 'LONELINESS', 10,
         CASE WHEN n <= 100 THEN 0 ELSE 10 END,
         CASE WHEN n <= 100 THEN 'DEFEATED' ELSE 'ALIVE' END,
         CASE WHEN n <= 100 THEN created_at + interval '3 minutes' ELSE NULL END,
         created_at
  FROM seed_other_posts
  RETURNING id, post_id, status
)
INSERT INTO monster_hp_log (monster_id, member_id, action, target_id, hp_delta, hp_before, hp_after, retroactive,
                            created_at)
SELECT id, :member_id, 'POST_LIKE', post_id, 1, 1, 0, false, now()
FROM spawned
WHERE status = 'DEFEATED';

-- 3. 내가 받은 알림 1만 개. 내 글 1천 개에 댓글 알림이 열 개씩이고, 다섯에 하나는 읽었다.
--    번호(seq)는 1부터 차례로, 시각은 지난 30일에 흩뿌린다(보관 기간 90일 안).
INSERT INTO notification (receiver_id, type, post_id, comment_id, latest_actor_id, actor_count, dedup_key, seq,
                          read_at, created_at, updated_at)
SELECT :member_id, 'POST_COMMENT', p.id, 1000000000 + g.n, :actor_id, 1, 'COMMENT:' || (1000000000 + g.n), g.n,
       CASE WHEN g.n % 5 = 0 THEN now() ELSE NULL END,
       now() - (interval '30 days' * (10000 - g.n) / 10000.0),
       now() - (interval '30 days' * (10000 - g.n) / 10000.0)
FROM generate_series(1, 10000) AS g(n)
     JOIN seed_my_posts p ON p.n = 1 + g.n % 1000;

INSERT INTO notification_sequence (member_id, last_seq)
VALUES (:member_id, 10000)
ON CONFLICT (member_id) DO UPDATE SET last_seq = EXCLUDED.last_seq;

COMMIT;

ANALYZE posts, comments, post_likes, monsters, monster_hp_log, notification;
