package dev.wallpep.cutout

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutRequestsTest {
    @Test fun concurrentActivityAndEnginesClaimOnlyOneAttemptPerPhoto() {
        val requests = CutoutRequests()
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..32).map {
                workers.submit<Boolean> {
                    start.await()
                    requests.claim("photo-a")
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(CutoutState.PROCESSING, requests.states.value["photo-a"])
            requests.complete("photo-a", CutoutState.READY)
            assertFalse(requests.claim("photo-a"))
            assertEquals(CutoutState.READY, requests.states.value["photo-a"])
            assertTrue(requests.claim("photo-b"))
            requests.complete("photo-b", CutoutState.UNAVAILABLE)
            assertFalse(requests.claim("photo-b"))
        } finally {
            workers.shutdownNow()
        }
    }
}
