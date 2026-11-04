package com.example.careguardcamera

import com.example.careguardcamera.data.ApiMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 긴급 상황 카운트다운 종료 시각처럼 서버 Instant를 비교할 때 쓰는 파서. 소수 자릿수가 들쭉날쭉해도 같은 값을 내야 한다. */
class ApiMappingTimeTest {
    private val base = 1_791_158_410_000L // 2026-10-05T00:00:10Z

    @Test
    fun acceptsAnyFractionLengthAndOffset() {
        assertEquals(base, ApiMapping.epochMillisOrNull("2026-10-05T00:00:10Z"))
        assertEquals(base + 100, ApiMapping.epochMillisOrNull("2026-10-05T00:00:10.1Z"))
        assertEquals(base + 120, ApiMapping.epochMillisOrNull("2026-10-05T00:00:10.12Z"))
        assertEquals(base + 123, ApiMapping.epochMillisOrNull("2026-10-05T00:00:10.123456Z"))
        assertEquals(base, ApiMapping.epochMillisOrNull("2026-10-05T09:00:10+09:00"))
        assertEquals(base, ApiMapping.epochMillisOrNull("2026-10-05T09:00:10+0900"))
    }

    @Test
    fun rejectsMissingOrMalformedValues() {
        assertNull(ApiMapping.epochMillisOrNull(null))
        assertNull(ApiMapping.epochMillisOrNull(""))
        assertNull(ApiMapping.epochMillisOrNull("2026-10-05 00:00:10"))
    }
}
