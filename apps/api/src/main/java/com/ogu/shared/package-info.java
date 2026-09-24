/**
 * 공유 모듈. 모든 모듈이 하위 패키지까지 접근할 수 있도록 OPEN 타입으로 선언한다.
 * Kotlin은 패키지 레벨 어노테이션을 지원하지 않으므로 Java 파일로 작성한다.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ogu.shared;
