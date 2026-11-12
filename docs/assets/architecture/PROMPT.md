# 구조도 생성 프롬프트

README의 구조도 두 장(`before-gcp.png`, `after-free.png`)을 만들 때 쓴 프롬프트다. 그림을 다시 만들 때 이 문구를 고쳐 쓴다.

## 공통 스타일

16:9 가로, 밝은 크림색 배경, 둥근 clay 질감의 귀여운 3D isometric 구조도. 민트 중심 파스텔, 약한 그림자. 노드는 둥근 블록이나 작은 건물/서버 모형이고, 각 노드에 큰 라벨과 짧은 한 줄 설명을 둔다. 화살표는 굵고 화살촉이 도착 노드를 향하며, 화살표마다 짧은 글자 라벨을 붙인다. 선 교차를 줄인다. 한글은 아래 문구를 글자 그대로 정확히 쓴다(오탈자 금지). 기술 이름은 영어 원문 그대로. 아래 목록에 없는 노드나 화살표는 그리지 않는다. 상단 제목은 크게.

## 1장: before-gcp.png

제목: "이전 구조 · GCP (2026년 11월 10일까지)"
부제: "VM 한 대에 API, DB, Redis"

노드:
1. "브라우저" — "회원"
2. "Cloud Run" 박스 안에 "Next.js 웹" — "화면 + BFF"
3. 큰 박스 "Compute Engine VM (e2-small)" 안에 네 개: "Caddy" — "HTTPS", "Spring Boot API" — "서버 여러 대 가능", "PostgreSQL 17" — "+ pgvector", "Redis" — "알림 신호 · 레이드"
4. "LLM 공급자" — "감정 분석"
5. "GCS" — "DB 백업"

화살표(이것만):
- 브라우저 → Next.js 웹: "페이지"
- Next.js 웹 → Caddy: "API 호출"
- 브라우저 → Caddy: "SSE 실시간" (점선)
- Caddy → Spring Boot API: "전달"
- Spring Boot API → PostgreSQL 17: "읽기/쓰기"
- Spring Boot API → Redis: "pub/sub · Lua"
- Spring Boot API → LLM 공급자: "분석 요청"
- PostgreSQL 17 → GCS: "매일 백업"

아래 작은 빨간 리본: "결제 계정이 닫혀 멈춤"

## 2장: after-free.png

제목: "지금 구조 · 월 0원 (2026년 11월 12일부터)"
부제: "Redis 없이 서버 한 대 · 무료 플랜"

노드:
1. "브라우저" — "회원"
2. "Vercel" 박스 안에 "Next.js 웹" — "화면 + BFF"
3. "Render Free" 박스 안에 "Spring Boot API" — "서버 1대 · 512MB". 그 안에 작은 칩 두 개: "알림 신호(메모리)", "레이드(메모리)"
4. "Neon Free" — "PostgreSQL + pgvector · 쉬면 잠듦"
5. "LLM 공급자" — "감정 분석" (특정 회사 로고를 넣지 않는다. 일반 AI 칩 아이콘)
6. "GitHub Actions" — "매일 백업 · 사용량 점검"
7. "Cloudflare R2" — "백업 14일"
8. "cron-job.org" — "10분마다 깨우기"
9. "Resend" — "재설정 메일"

화살표(이것만):
- 브라우저 → Next.js 웹: "페이지"
- Next.js 웹 → Spring Boot API: "API 호출"
- 브라우저 → Spring Boot API: "SSE 실시간" (실선)
- Spring Boot API → Neon Free: "읽기/쓰기"
- Spring Boot API → LLM 공급자: "분석 요청"
- Spring Boot API → Resend: "SMTP 2587" (점선)
- cron-job.org → Spring Boot API: "liveness" (점선)
- GitHub Actions → Neon Free: "pg_dump" (점선)
- GitHub Actions → Cloudflare R2: "백업 저장" (점선)

작은 배지: Vercel, Render Free, Neon Free 옆에 초록 배지 "운영 중". GitHub Actions, Cloudflare R2, cron-job.org, Resend 옆에 회색 배지 "준비 중". 점선은 아직 켜지 않은 연결이라는 범례를 오른쪽 아래에 작게 둔다: "점선 = 준비 중".
