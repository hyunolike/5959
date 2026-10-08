package com.ogu.support

import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

/** 연결 표 발급(`POST /api/v1/notifications/stream-tickets`)을 실제 API로 부른다. */
class StreamTickets(
    private val mockMvc: MockMvc,
) {
    private val jsonMapper = JsonMapper.builder().build()

    fun request(member: TestMember): ResultActions = mockMvc.perform(post(PATH).bearer(member.accessToken))

    /** 발급된 표의 원문. */
    fun issue(member: TestMember): String {
        val body =
            request(member)
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return jsonMapper
            .readTree(body)
            .get("data")
            .get("ticket")
            .asString()
    }

    companion object {
        const val PATH = "/api/v1/notifications/stream-tickets"
    }
}
