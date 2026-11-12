# syntax=docker/dockerfile:1
# 빌드는 CI의 Gradle이 한다(bootJar). 이미지는 JAR만 담는다.
# 018 research R1: Render 무료(메모리 512MB, CPU 0.1개)에서 돈다.
#
# 단계 셋:
# - runtime: 캐시 없이 뜨는 이미지. CI 스모크 테스트와 e2e가 쓴다. 기본 대상(맨 아래)도 이것이다.
# - with-aot: runtime에 기동 캐시(AOT)를 더한다. 캐시는 DB가 있어야 만들어져서(Modulith JDBC 이벤트 저장소가
#   기동 중 DB 종류를 묻는다) Dockerfile 안에서 만들지 못한다. infra/render/scripts/build-image.sh가 runtime
#   이미지로 학습 실행을 한 뒤 build/aot/app.aot에 두고 이 단계를 빌드한다.
FROM eclipse-temurin:25-jre AS runtime

ARG OTEL_AGENT_VERSION=2.31.1
WORKDIR /app

# 운영에서만 JAVA_TOOL_OPTIONS로 켠다. 설정은 표준 OTEL_* 환경변수로 한다.
# Render 무료에서는 켜지 않는다. CPU 0.1개에서는 기동이 크게 늘어난다(research R1).
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /app/otel/opentelemetry-javaagent.jar

RUN groupadd --system app && useradd --system --gid app app
# 기동 캐시는 jar가 풀린 모양(실행 jar + lib/)에서만 쓸 수 있어 미리 푼다.
COPY build/libs/api.jar /tmp/api.jar
RUN java -Djarmode=tools -jar /tmp/api.jar extract --destination /app/run && rm /tmp/api.jar
USER app
WORKDIR /app/run

EXPOSE 8080
# - SerialGC, C1만(TieredStopAtLevel=1): 적은 CPU에서 병렬 GC와 C2 컴파일 스레드가 CPU를 먹지 않게 한다.
# - 힙 192MB: Render에서 224MB로 50명 부하를 걸었을 때 메모리가 506MB까지 올라 줄였다(research R1).
# - -Xms를 정해 둔다. 기동 캐시는 학습과 실행의 초기 힙이 맞아야 쓸 수 있다(build-image.sh와 같은 값).
ENTRYPOINT ["java", \
    "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", \
    "-Xms64m", "-Xmx192m", "-XX:MaxMetaspaceSize=150m", "-XX:ReservedCodeCacheSize=48m", "-Xss512k", \
    "-jar", "api.jar"]

FROM runtime AS with-aot
COPY --chown=app:app build/aot/app.aot /app/run/app.aot
ENV JDK_JAVA_OPTIONS="-XX:AOTCache=/app/run/app.aot"

FROM runtime
