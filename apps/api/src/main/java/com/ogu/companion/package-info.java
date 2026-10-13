/**
 * AI 친구 모듈. 글이 올라온 뒤 한동안 다른 회원의 댓글이 없으면 AI 친구 한 명이 댓글 하나와 공감 하나를 남긴다.
 * 글마다 반응 기록을 하나 두고 주기 작업이 차례가 된 것을 맡는다. 댓글과 공감은
 * {@link com.ogu.post.PostCompanionApi}로 남겨 사람의 댓글과 같은 이벤트가 나가고, 글의 위험 판정은
 * {@link com.ogu.safety.RiskApi}로 묻는다. 이 모듈에 의존하는 모듈은 없다(009 research R1).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "safety", "emotion", "ai"})
package com.ogu.companion;
