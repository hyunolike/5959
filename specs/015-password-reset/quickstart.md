# Quickstart: 비밀번호 재설정 검증 (015-password-reset)

## 자동 검증

```bash
cd apps/api && ./gradlew test --tests "*PasswordReset*" --tests "*ContractTests*" --tests "*ModularityTests*" --tests "*Withdrawal*"
cd apps/api && ./gradlew ktlintCheck detekt
pnpm --filter web test
pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web typecheck
pnpm --filter web test:e2e:full          # password-reset.spec.ts
bash infra/tests/deploy_test.sh
```

## 수동 검증 (로컬, e2e 프로필)

e2e 프로필은 메일을 보내지 않고, 링크의 값이 이메일에서 정해진다(research R2). 값은 `e2e-` 뒤에 `sha256("reset:" + 이메일)`의 16진수다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | 로그인 화면에서 이메일 로그인을 펼친다 | "비밀번호를 잊으셨나요?"가 있다 | US1-AC1 |
| 2 | 가입한 이메일과 가입하지 않은 이메일로 차례로 요청한다 | 두 번 다 같은 안내다 | US1-AC3, US1-AC5 |
| 3 | `/reset-password?token=<값>`을 열어 새 비밀번호를 정한다 | 로그인 화면으로 가고 "비밀번호를 바꿨어요"가 보인다 | US2-AC1 |
| 4 | 새 비밀번호와 옛 비밀번호로 로그인한다 | 새 것만 된다 | US2-AC2 |
| 5 | 같은 주소를 다시 열어 저장한다 | 링크를 쓸 수 없다는 안내와 다시 요청하는 길 | US2-AC3 |
| 6 | API를 `MAIL_ENABLED=false`로 띄우고 로그인 화면을 본다 | 들어가는 길이 없다. `/forgot-password`를 열면 지금은 쓸 수 없다는 안내 | US4-AC1, US4-AC2 |

## 켜는 방법 (운영)

1. 메일 발송 서비스를 정하고(SMTP를 받는 곳이면 된다) 보내는 주소의 도메인에 SPF와 DKIM을 건다.
2. API의 환경에 넣는다: `MAIL_ENABLED=true`, `MAIL_FROM`, `MAIL_SMTP_HOST`, `MAIL_SMTP_PORT`, `MAIL_SMTP_USERNAME`, `MAIL_SMTP_PASSWORD`, `OGU_WEB_ORIGIN=https://new.5959.site`. GCP에서는 비밀 값(JSON)의 같은 이름 필드다.
3. API를 다시 올린다. 값이 빠졌으면 뜨지 않는다.
4. 내 계정으로 재설정을 요청해 메일이 받은편지함에 오는지(스팸함이 아닌지) 본다. 1분 안에 로그인 화면에 들어가는 길이 보인다.

비용이 드는 서비스를 고르면 ADR로 남긴다(원칙 VI).

## 배포

병합하면 웹은 바로 배포된다. **API는 따로 올려야 한다.** 올리면 `V15`가 테이블 하나를 만든다. 꺼진 채로 나가므로 올린 뒤에도 화면은 지금과 같다.
