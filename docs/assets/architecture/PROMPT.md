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
7. "Actions 아티팩트" — "암호화 백업 14일"
8. "cron-job.org" — "10분마다 깨우기"
9. "Resend" — "재설정 메일"

화살표(이것만):
- 브라우저 → Next.js 웹: "페이지"
- Next.js 웹 → Spring Boot API: "API 호출"
- 브라우저 → Spring Boot API: "SSE 실시간" (실선)
- Spring Boot API → Neon Free: "읽기/쓰기"
- Spring Boot API → LLM 공급자: "분석 요청"
- Spring Boot API → Resend: "SMTP 2587" (점선)
- cron-job.org → Spring Boot API: "liveness" (실선)
- GitHub Actions → Neon Free: "pg_dump" (실선)
- GitHub Actions → Actions 아티팩트: "gpg 암호화" (실선)

작은 배지: Vercel, Render Free, Neon Free, cron-job.org, GitHub Actions 옆에 초록 배지 "운영 중". Resend 옆에 회색 배지 "준비 중". 오른쪽 아래 작은 범례: "점선 = 준비 중".

## 3장: request-flow.png

제목: "요청이 처리되는 길"
부제: "웹이 쿠키를 토큰으로 바꿔 API에 전한다"

노드(왼쪽에서 오른쪽):
1. "브라우저" — "회원"
2. "Vercel" 박스 안에 "Next.js BFF" — "쿠키 → Bearer 토큰"
3. "Render Free" 박스 안에 "Spring Boot API". 그 안에 작은 칩 세 개: "글 · 피드", "레이드(메모리 판정)", "알림 SSE"
4. "Neon Free" — "PostgreSQL"
5. "LLM 공급자" — "감정 분석 · 위험 분류" (특정 회사 로고 없이 일반 AI 칩 아이콘)

화살표(이것만, 번호 배지를 붙인다):
- ① 브라우저 → Next.js BFF: "요청 + 로그인 쿠키"
- ② Next.js BFF → Spring Boot API: "Bearer 토큰 + BFF 키"
- ③ Spring Boot API → Neon Free: "읽기/쓰기"
- ④ "글 · 피드" 칩 → LLM 공급자: "글 저장 뒤 분석" (점선. 반드시 "글 · 피드" 칩의 오른쪽 끝에서 시작한다. 레이드 칩에서 나오면 안 된다)
- ⑤ 브라우저 → Spring Boot API의 "알림 SSE" 칩: "일회용 표로 직접 연결" (실선, 브라우저에서 BFF를 건너뛰는 긴 화살표)
- ⑥ "레이드(메모리 판정)" 칩 → Neon Free: "1초마다 기록"

범례(오른쪽 아래 작게): "점선 = 응답을 기다리지 않는 뒤 작업"

## 4장: deploy-flow.png

제목: "배포와 운영"
부제: "무료 플랜 위에서 돌리는 방법"

노드:
1. "개발자" — "로컬"
2. "GitHub" — "저장소 · PR CI"
3. "Release 워크플로" — "손으로 실행"
4. "GHCR" — "API 이미지(AOT 캐시)"
5. "Render Free" — "Spring Boot API"
6. "Vercel" — "Next.js 웹"
7. "cron-job.org" — "10분마다 깨우기"
8. "Ops daily 워크플로" — "매일 03:30"
9. "Neon Free" — "PostgreSQL"
10. "Actions 아티팩트" — "암호화 백업 14일"

화살표(이것만):
- 개발자 → GitHub: "PR · 병합"
- GitHub → Release 워크플로: "버전 확정"
- Release 워크플로 → GHCR: "이미지 올리기"
- Release 워크플로 → Render Free: "API로 배포 · live 대기"
- GHCR → Render Free: "이미지 받기"
- 개발자 → Vercel: "vercel deploy --prod"
- cron-job.org → Render Free: "liveness"
- Ops daily 워크플로 → Neon Free: "pg_dump · 사용량 점검"
- Ops daily 워크플로 → Actions 아티팩트: "gpg 암호화"
- Render Free → Neon Free: "읽기/쓰기"

작은 배지: Release 워크플로와 개발자→Vercel 화살표 옆에 "손으로", cron-job.org와 Ops daily 옆에 "자동".
