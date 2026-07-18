package com.legion.orion.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamJsonEventMapperTelemetryTest {
    private val json = Json

    @Test
    fun tracksLiveContextFromMessageUsageAndResultWindow() {
        val mapper = StreamJsonEventMapper()
        mapper.accept(obj("""{"type":"stream_event","event":{"type":"message_start","message":{"usage":{"input_tokens":100,"cache_read_input_tokens":900,"cache_creation_input_tokens":10}}}}"""))
        mapper.accept(obj("""{"type":"stream_event","event":{"type":"message_delta","usage":{"output_tokens":50}}}"""))
        mapper.accept(obj("""{"type":"result","usage":{},"modelUsage":{"claude-sonnet":{"contextWindow":200000}}}"""))

        assertEquals(1_060L, mapper.liveContextUsage.usedTokens)
        assertEquals(200_000L, mapper.liveContextUsage.contextWindowTokens)
    }

    @Test
    fun normalizesRateLimitUtilizationToPercentage() {
        val mapper = StreamJsonEventMapper()
        mapper.accept(obj("""{"type":"rate_limit_event","rate_limit_info":{"rateLimitType":"five_hour","utilization":0.43,"resetsAt":2000000000}}"""))

        assertEquals(43.0, mapper.fiveHourRateLimit?.usedPercentage ?: -1.0, 0.001)
        assertEquals(2_000_000_000L, mapper.fiveHourRateLimit?.resetsAtEpochSeconds)
    }

    private fun obj(source: String): JsonObject = json.parseToJsonElement(source) as JsonObject
}
