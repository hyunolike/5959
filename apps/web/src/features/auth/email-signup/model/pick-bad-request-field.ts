/**
 * 가입 400(INVALID_REQUEST)은 이메일 형식 위반과 비밀번호 규칙 위반을 같은
 * 코드로 뭉뚱그린다(SignupApiTests). 서버 메시지에 "이메일"이 있으면 이메일
 * 필드 문제로, 그 외에는(대개 비밀번호 규칙 위반) 비밀번호 필드 문제로 본다.
 */
export function pickSignup400Field(message: string): "email" | "password" {
  return message.includes("이메일") ? "email" : "password";
}
