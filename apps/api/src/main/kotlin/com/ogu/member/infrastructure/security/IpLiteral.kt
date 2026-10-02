package com.ogu.member.infrastructure.security

/**
 * IP 리터럴을 검증하고 표준 표기로 바꾼다. 문자열만 해석하고 DNS 조회는 하지 않는다(`InetAddress.getByName`은 리터럴이
 * 아니면 호스트 이름으로 조회한다).
 *
 * - IPv4: 점으로 나눈 0~255 네 칸. 두 자리 이상에서 앞자리 0은 받지 않는다(8진수로 읽히는 표기와 헷갈리지 않도록).
 * - IPv6: RFC 4291 표기(`::` 생략, 끝의 IPv4 표기 포함). 존 ID(`%eth0`)는 받지 않는다. 출력은 RFC 5952 표기다.
 * - IPv4 매핑 IPv6(`::ffff:a.b.c.d`)는 같은 클라이언트이므로 IPv4 표기로 바꾼다.
 * - 45자(IPv6 표기의 최대 길이)를 넘으면 받지 않는다.
 */
object IpLiteral {
    const val MAX_LENGTH = 45

    private const val IPV4_PARTS = 4
    private const val IPV6_GROUPS = 8
    private const val MAX_OCTET = 255
    private const val MAX_HEX_DIGITS = 4
    private const val MAPPED_PREFIX_GROUPS = 5
    private const val MAPPED_MARKER = 0xffff
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xff
    private val DECIMAL = Regex("^(0|[1-9][0-9]{0,2})$")
    private val HEX_GROUP = Regex("^[0-9A-Fa-f]{1,$MAX_HEX_DIGITS}$")

    /** 표준 표기를 돌려준다. IP 리터럴이 아니면 null. */
    fun canonicalize(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_LENGTH) return null
        return if (':' in value) parseIpv6(value)?.let(::formatIpv6) else parseIpv4(value)?.let(::formatIpv4)
    }

    private fun parseIpv4(value: String): IntArray? {
        val parts = value.split('.')
        if (parts.size != IPV4_PARTS || parts.any { !DECIMAL.matches(it) }) return null
        val octets = parts.map { it.toInt() }
        return if (octets.all { it <= MAX_OCTET }) octets.toIntArray() else null
    }

    private fun parseIpv6(value: String): IntArray? {
        val halves = value.split("::")
        return when (halves.size) {
            1 -> parseGroups(value, isLast = true)?.takeIf { it.size == IPV6_GROUPS }?.toIntArray()
            2 -> {
                val head = if (halves[0].isEmpty()) emptyList() else parseGroups(halves[0], isLast = false)
                val tail = if (halves[1].isEmpty()) emptyList() else parseGroups(halves[1], isLast = true)
                if (head == null || tail == null || head.size + tail.size >= IPV6_GROUPS) return null
                (head + List(IPV6_GROUPS - head.size - tail.size) { 0 } + tail).toIntArray()
            }
            else -> null
        }
    }

    /** `:`로 나눈 16비트 그룹들. [isLast]이면 마지막 칸에 IPv4 표기를 허용한다(그룹 두 개가 된다). */
    private fun parseGroups(
        value: String,
        isLast: Boolean,
    ): List<Int>? {
        val parts = value.split(':')
        val groups =
            parts.mapIndexed { index, part ->
                when {
                    HEX_GROUP.matches(part) -> listOf(part.toInt(radix = 16))
                    isLast && index == parts.lastIndex && '.' in part ->
                        parseIpv4(part)?.let { listOf((it[0] shl BYTE_BITS) or it[1], (it[2] shl BYTE_BITS) or it[3]) }
                    else -> null
                }
            }
        return if (groups.any { it == null }) null else groups.flatMap { requireNotNull(it) }
    }

    private fun formatIpv4(octets: IntArray): String = octets.joinToString(".")

    private fun formatIpv6(groups: IntArray): String {
        val mapped =
            (0 until MAPPED_PREFIX_GROUPS).all { groups[it] == 0 } && groups[MAPPED_PREFIX_GROUPS] == MAPPED_MARKER
        val (start, length) = longestZeroRun(groups)
        return when {
            mapped -> {
                val high = groups[MAPPED_PREFIX_GROUPS + 1]
                val low = groups[MAPPED_PREFIX_GROUPS + 2]
                formatIpv4(intArrayOf(high shr BYTE_BITS, high and BYTE_MASK, low shr BYTE_BITS, low and BYTE_MASK))
            }
            length < 2 -> hex(groups.asList())
            else -> "${hex(groups.take(start))}::${hex(groups.drop(start + length))}"
        }
    }

    private fun hex(groups: List<Int>): String = groups.joinToString(":") { it.toString(radix = 16) }

    /** 가장 긴 0 그룹 연속 구간(같으면 앞쪽). RFC 5952 4.2.3. */
    private fun longestZeroRun(groups: IntArray): Pair<Int, Int> {
        var bestStart = -1
        var bestLength = 0
        var index = 0
        while (index < groups.size) {
            if (groups[index] != 0) {
                index++
                continue
            }
            val start = index
            while (index < groups.size && groups[index] == 0) index++
            if (index - start > bestLength) {
                bestStart = start
                bestLength = index - start
            }
        }
        return bestStart to bestLength
    }
}
