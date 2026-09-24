package com.ogu

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

class ModularityTests {
    private val modules = ApplicationModules.of(OguApplication::class.java)

    @Test
    fun `모듈 경계와 의존 규칙을 검증한다`() {
        modules.verify()
    }

    @Test
    fun `모듈 구조 문서를 생성한다`() {
        // build/spring-modulith-docs 아래에 C4/PlantUML 다이어그램과 모듈 캔버스 생성
        Documenter(modules).writeDocumentation()
    }
}
