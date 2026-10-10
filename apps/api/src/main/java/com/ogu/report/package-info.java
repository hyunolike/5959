/**
 * 주간 리포트 모듈. 매주 지난주에 글을 쓴 회원마다 그 주의 수치를 세어 저장하고, AI가 수치로 쓴 짧은 편지를 붙인다.
 * 다른 모듈의 데이터는 파사드로만 읽는다. 리포트를 넣으면 {@link com.ogu.report.WeeklyReportPublished}를 내고
 * {@code notification}이 받아 알린다(008 research R1). {@code member}는 조회 API가 로그인한 회원을
 * 받는 데만 쓴다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared", "post", "monster", "emotion", "ai", "member"})
package com.ogu.report;
