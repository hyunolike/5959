package com.ogu.member.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuthSessionRepository : JpaRepository<AuthSession, UUID>
