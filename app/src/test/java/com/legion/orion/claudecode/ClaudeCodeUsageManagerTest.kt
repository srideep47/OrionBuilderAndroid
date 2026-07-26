package com.legion.orion.claudecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeCodeUsageManagerTest {
    @Test
    fun parsesSessionWeeklyAndFableWindows() {
        val usage = ClaudeCodeUsageManager.parseUsageResponse(
            """{
              "five_hour":{"utilization":50,"resets_at":"2026-07-17T13:40:00Z"},
              "seven_day":{"utilization":34,"resets_at":"2026-07-17T15:00:00Z"},
              "limits":[
                {"kind":"session","percent":50,"resets_at":"2026-07-17T13:40:00Z"},
                {"kind":"weekly_all","percent":34,"resets_at":"2026-07-17T15:00:00Z"},
                {"kind":"weekly_scoped","percent":0,"resets_at":null,
                 "scope":{"model":{"display_name":"Fable"}}}
              ]
            }""",
        )

        assertEquals(50.0, usage.fiveHour.usedPercentage ?: -1.0, 0.001)
        assertEquals(34.0, usage.sevenDay.usedPercentage ?: -1.0, 0.001)
        assertEquals("Fable", usage.modelScoped.single().displayName)
        assertEquals(0.0, usage.modelScoped.single().usedPercentage ?: -1.0, 0.001)
        assertNull(usage.modelScoped.single().resetsAtEpochSeconds)
    }

    @Test
    fun normalizesFractionalUtilizationFromUsageEndpoint() {
        val usage = ClaudeCodeUsageManager.parseUsageResponse(
            """{"five_hour":{"utilization":0.43},"seven_day":{"used_percentage":0.07}}""",
        )

        assertEquals(43.0, usage.fiveHour.usedPercentage ?: -1.0, 0.001)
        assertEquals(7.0, usage.sevenDay.usedPercentage ?: -1.0, 0.001)
    }
}
