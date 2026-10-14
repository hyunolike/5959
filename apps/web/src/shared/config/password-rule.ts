/**
 * 비밀번호 규칙(002 US1-AC3, data-model.md): 8~20자, 영문 1자 이상과 숫자 1자 이상 포함.
 * 가입(002)과 비밀번호 재설정(015)이 함께 쓴다. API의 `PasswordPolicy`와 같은 규칙이다.
 */
export const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,20}$/;

export const PASSWORD_RULE_MESSAGE =
  "비밀번호는 영문과 숫자를 포함해 8~20자로 입력하세요.";
