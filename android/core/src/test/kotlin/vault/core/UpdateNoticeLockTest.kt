package vault.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FETCH_HOLD_MS = 1_500L
private const val QUICK_MS = 500L

private class SlowStore(override var checksEnabled: Boolean = true, override var dismissedVersion: String = "") : UpdateNoticeStore

private class HeldFetch(private val version: String = "1.5.0") {
    val started = CountDownLatch(1)
    private val release = CountDownLatch(1)

    fun fetch(): String {
        started.countDown()
        release.await()
        return version
    }

    fun releaseAfter(millis: Long) = thread {
        Thread.sleep(millis)
        release.countDown()
    }
}

class UpdateNoticeLockTest {
    @Test
    fun dismissingDoesNotWaitForAFetchInFlight() {
        val held = HeldFetch()
        val notice = UpdateNotice("1.4.0", SlowStore(), held::fetch, {})
        val checking = thread { notice.checkIfDue() }
        assertTrue(held.started.await(2, TimeUnit.SECONDS))
        held.releaseAfter(FETCH_HOLD_MS)
        val tookMillis = measureTimeMillis { notice.dismiss() }
        checking.join()
        assertTrue(tookMillis < QUICK_MS, "dismiss took $tookMillis ms")
    }

    @Test
    fun turningChecksOffDuringAFetchLeavesNoBanner() {
        val store = SlowStore()
        val held = HeldFetch()
        val notice = UpdateNotice("1.4.0", store, held::fetch, {})
        var shown: String? = "unset"
        val checking = thread { shown = notice.checkIfDue() }
        assertTrue(held.started.await(2, TimeUnit.SECONDS))
        held.releaseAfter(FETCH_HOLD_MS)
        val tookMillis = measureTimeMillis { notice.turnChecks(false) }
        checking.join()
        assertTrue(tookMillis < QUICK_MS, "turnChecks took $tookMillis ms")
        assertNull(shown)
        assertNull(notice.availableVersion())
    }
}
