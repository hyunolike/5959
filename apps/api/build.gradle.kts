plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

group = "com.ogu"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    // 003-core-loop 감정 분석(research): Batch 4가 ogu.ai.*에서 직접 OpenAiApi를 만들어 쓴다.
    // 스타터의 OpenAiChatAutoConfiguration은 spring.ai.openai.* 자동설정이라 여기서는 끈다(아래 application.yml 참고).
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")

    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")
    runtimeOnly("org.postgresql:postgresql")

    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.1부터 TestRestTemplate이 별도 모듈로 분리됨(ContractTests가 실제 HTTP로 /v3/api-docs를 읽을 때 씀)
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-docs")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.48")
    // swagger-parser가 끌어오는 io.swagger:swagger-core(1.x, v2 변환용)는 JDK에서 제거된 JAXB를 참조한다
    testRuntimeOnly("javax.xml.bind:jaxb-api:2.3.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // annotation-default-target: Kotlin 2.2의 미래 기본값(param + property)을 미리 적용 (KT-73255)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

ktlint {
    version.set("1.7.1") // Kotlin 2.2.21 호환 버전으로 고정
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/detekt.yml"))
}

// detekt는 자신이 컴파일된 Kotlin 버전으로 실행되어야 한다 (프로젝트 Kotlin 버전과 독립)
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(
                io.gitlab.arturbosch.detekt
                    .getSupportedKotlinVersion(),
            )
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName.set("api.jar")
}

tasks.jar {
    enabled = false
}
