package com.ogu.notification.presentation.dto

import com.ogu.member.StreamTicket
import java.time.Instant

/** 계약의 `StreamTicket`. */
data class StreamTicketResponse(
    val ticket: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(ticket: StreamTicket): StreamTicketResponse = StreamTicketResponse(ticket.ticket, ticket.expiresAt)
    }
}
