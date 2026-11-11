package it.eja.vox

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ApiRouterTest {
    private class Quota : ApiQuota {
        val cooldown = mutableMapOf<String, Long>()
        val counts = mutableMapOf<Pair<String, Long>, Int>()
        override fun blockedUntil(host: String) = cooldown[host] ?: 0L
        override fun block(host: String, until: Long) { cooldown[host] = until }
        override fun used(id: String, day: Long) = counts[id to day] ?: 0
        override fun reserve(id: String, day: Long) { counts[id to day] = used(id, day) + 1 }
    }
    private fun profile(id: String, host: String, free: Boolean = true, cap: Int = 0) =
        ApiProfile(id, id, "https://$host/v1/audio/transcriptions", "private-key", "model", true, free, cap)

    @Test fun rateLimitSkipsSameProviderKeysAndHonorsRetryAfter() {
        val quota = Quota()
        val attempts = mutableListOf<String>()
        val router = ApiRouter(quota) { 1000L }
        val result = router.run(listOf(profile("a", "a.example"), profile("a2", "a.example"), profile("b", "b.example")), true, {}) {
            attempts.add(it.id)
            if (it.id == "a") throw ApiHttpError(429, 600)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf("a", "b"), attempts)
        assertEquals(601000L, quota.cooldown["a.example"])
    }

    @Test fun freeModeNeverSendsToPaidUnconfirmedOrDisabledProfiles() {
        val profiles = listOf(profile("paid", "paid.example", false), profile("disabled", "off.example").copy(enabled = false))
        var calls = 0
        try { ApiRouter(Quota()).run(profiles, true, {}) { calls++; "bad" }; fail() }
        catch (e: ApiUnavailable) { assertEquals(0, calls) }
    }

    @Test fun localBudgetReservesFailedRequestsAndResetsNextUtcDay() {
        val quota = Quota()
        var now = 0L
        val router = ApiRouter(quota) { now }
        val p = profile("a", "a.example", cap = 1)
        try { router.run(listOf(p), true, {}) { throw IOException() }; fail() } catch (_: ApiUnavailable) { }
        assertEquals(1, quota.used("a", 0))
        now = 120000
        var calls = 0
        try { router.run(listOf(p), true, {}) { calls++; "bad" }; fail() } catch (_: ApiUnavailable) { }
        assertEquals(0, calls)
        now = 86_400_001
        assertEquals("ok", router.run(listOf(p), true, {}) { "ok" })
    }

    @Test fun invalidInputDoesNotMultiplyRequestsAcrossProviders() {
        val attempts = mutableListOf<String>()
        try {
            ApiRouter(Quota()).run(listOf(profile("a", "a.example"), profile("b", "b.example")), true, {}) {
                attempts.add(it.id); throw ApiHttpError(400)
            }
            fail()
        } catch (_: ApiUnavailable) { assertEquals(listOf("a"), attempts) }
    }

    @Test fun allNetworksFailOnlyOncePerEndpointAndRemainInCooldown() {
        val quota = Quota()
        val router = ApiRouter(quota) { 1000L }
        val profiles = listOf(profile("a", "a.example"), profile("b", "b.example"))
        var calls = 0
        repeat(2) {
            try { router.run(profiles, true, {}) { calls++; throw IOException() }; fail() } catch (_: ApiUnavailable) { }
        }
        assertEquals(2, calls)
    }

    @Test fun plaintextAndCredentialBearingUrlsAreNotContacted() {
        var calls = 0
        val p = profile("a", "a.example")
        try {
            ApiRouter(Quota()).run(listOf(p.copy(url = "http://a.example"), p.copy(url = "https://secret@a.example")), true, {}) { calls++; "bad" }
            fail()
        } catch (_: ApiUnavailable) { assertEquals(0, calls) }
    }

    @Test fun expiredAndInvalidFreeTrialDatesAreSkippedBeforeSending() {
        val router = ApiRouter(Quota()) { 86_400_000L }
        val a = profile("expired", "a.example").copy(freeUntil = "1970-01-01")
        val b = profile("invalid", "b.example").copy(freeUntil = "2026-02-30")
        val c = profile("valid", "c.example").copy(freeUntil = "1970-01-02")
        val attempts = mutableListOf<String>()
        assertEquals("ok", router.run(listOf(a, b, c), true, {}) { attempts.add(it.id); "ok" })
        assertEquals(listOf("valid"), attempts)
    }
    @Test fun encryptedWebSocketCanParticipateInFallback() {
        val a = profile("ws", "a.example").copy(url = "wss://a.example/api-ws/v1/inference")
        assertEquals("ok", ApiRouter(Quota()).run(listOf(a), true, {}) { "ok" })
    }

}
