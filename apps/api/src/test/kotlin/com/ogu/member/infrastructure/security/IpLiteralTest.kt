package com.ogu.member.infrastructure.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class IpLiteralTest {
    @ParameterizedTest
    @CsvSource(
        "203.0.113.7, 203.0.113.7",
        "' 10.0.0.1 ', 10.0.0.1",
        "0.0.0.0, 0.0.0.0",
        "255.255.255.255, 255.255.255.255",
        "2001:DB8::1, 2001:db8::1",
        "2001:0db8:0000:0000:0000:0000:0000:0001, 2001:db8::1",
        "2001:db8:0:0:1:0:0:1, 2001:db8::1:0:0:1",
        "2001:db8:0:1:1:1:1:1, 2001:db8:0:1:1:1:1:1",
        "::, ::",
        "::1, ::1",
        "fe80::, fe80::",
        "0000:0000:0000:0000:0000:ffff:192.168.100.200, 192.168.100.200",
        "::ffff:203.0.113.7, 203.0.113.7",
        "64:ff9b::192.0.2.33, 64:ff9b::c000:221",
    )
    fun `IP 리터럴을 표준 표기로 바꾼다`(
        raw: String,
        expected: String,
    ) {
        assertThat(IpLiteral.canonicalize(raw)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            " ",
            "localhost",
            "example.com",
            "abc.def",
            "1.2.3",
            "1.2.3.4.5",
            "256.1.1.1",
            "01.2.3.4",
            "1..2.3",
            "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7",
            "1::2::3",
            ":::",
            "12345::1",
            "fe80::1%eth0",
            "::g",
            "1.2.3.4::",
            "::1.2.3.4:1",
            "evil-ip-value",
        ],
    )
    fun `IP 리터럴이 아니면 null`(raw: String) {
        assertThat(IpLiteral.canonicalize(raw)).isNull()
    }

    @Test
    fun `45자를 넘으면 null`() {
        val longest = "0000:0000:0000:0000:0000:ffff:192.168.100.200"
        assertThat(longest).hasSize(45)
        assertThat(IpLiteral.canonicalize(longest)).isNotNull()
        assertThat(IpLiteral.canonicalize("0$longest")).isNull()
        assertThat(IpLiteral.canonicalize("1".repeat(300))).isNull()
    }
}
