# syntax=docker/dockerfile:1
# 빌드는 CI의 Gradle이 한다(bootJar). 이미지는 JAR만 담아서 arm64 빌드에 QEMU 컴파일이 필요 없게 한다.
FROM eclipse-temurin:21-jre

ARG OTEL_AGENT_VERSION=2.31.1
WORKDIR /app

# 운영에서만 JAVA_TOOL_OPTIONS로 켠다. 설정은 표준 OTEL_* 환경변수로 한다.
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /app/otel/opentelemetry-javaagent.jar

RUN groupadd --system app && useradd --system --gid app app
COPY build/libs/api.jar /app/app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
