package com.ogu.member.domain

/** 회원의 종류(009 research R2). AI 회원은 마이그레이션의 시드로만 생기고 로그인 수단이 없다. */
enum class MemberKind {
    HUMAN,
    AI,
}
