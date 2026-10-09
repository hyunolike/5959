package com.ogu.safety.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** `safety_term` 저장소(005 research R4). [TermRow.term]은 정규화한 꼴이다. 판정은 `TermCache`가 올려 둔 목록으로 한다. */
@Repository
class SafetyTermRepository(
    private val jdbcClient: JdbcClient,
) {
    fun list(kind: TermKind?): List<TermRow> {
        val where = if (kind == null) "" else "where kind = :kind"
        var spec = jdbcClient.sql("select id, kind, term from safety_term $where order by kind, term")
        if (kind != null) spec = spec.param("kind", kind.name)
        return spec.query { rs, _ -> rs.toTerm() }.list()
    }

    fun find(
        kind: TermKind,
        term: String,
    ): TermRow? =
        jdbcClient
            .sql("select id, kind, term from safety_term where kind = :kind and term = :term")
            .param("kind", kind.name)
            .param("term", term)
            .query { rs, _ -> rs.toTerm() }
            .optional()
            .orElse(null)

    /** 낱말을 넣는다. 이미 있으면 아무것도 하지 않고 null을 돌려준다. */
    fun insertIfAbsent(
        kind: TermKind,
        term: String,
        now: Instant,
    ): TermRow? =
        jdbcClient
            .sql(
                """
                insert into safety_term (kind, term, created_at, updated_at)
                values (:kind, :term, :now, :now)
                on conflict on constraint safety_term_kind_term_key do nothing
                returning id, kind, term
                """.trimIndent(),
            ).param("kind", kind.name)
            .param("term", term)
            .param("now", Timestamp.from(now))
            .query { rs, _ -> rs.toTerm() }
            .optional()
            .orElse(null)

    /** 낱말을 뺀다. 없었으면 false다. */
    fun delete(id: Long): Boolean =
        jdbcClient
            .sql("delete from safety_term where id = :id")
            .param("id", id)
            .update() == 1

    private fun ResultSet.toTerm(): TermRow {
        val kind = TermKind.valueOf(getString("kind"))
        return TermRow(termId = getLong("id"), kind = kind, term = getString("term"))
    }
}

/** CRISIS 위기 표현, CONCERN 우려 표현, PROFANITY 욕설, ALLOW 욕설이 들어 있어도 가리지 않을 낱말. */
enum class TermKind {
    CRISIS,
    CONCERN,
    PROFANITY,
    ALLOW,
}

/** 계약의 `Term`. */
data class TermRow(
    val termId: Long,
    val kind: TermKind,
    val term: String,
)
