package vault.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val UNUSED_SERVER = "http://127.0.0.1:1"
private const val LOCK_RACE_TRIALS = 400
private const val MAX_SPIN_BEFORE_LOCK = 20_000

class SessionLockRaceTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempStore(): LocalStore = LocalStore(Files.createTempDirectory("lock-race").toFile().also { tempDirs += it })

    private fun oneByteSource() = PlainSource { ByteArrayInputStream(byteArrayOf(7)) }

    private fun addFilesUntilLocked(session: VaultSession, started: CountDownLatch): Thread = thread {
        started.countDown()
        while (true) {
            try {
                session.addFile("photo.jpg", "image/jpeg", 1, 1L, oneByteSource(), null)
            } catch (locked: IllegalStateException) {
                return@thread
            }
        }
    }

    private fun spin(rounds: Int): Long {
        var sink = 0L
        repeat(rounds) { sink += it }
        return sink
    }

    @Test
    fun lockingWhileFilesAreAddedNeverSealsDetailsWithAZeroedKey() {
        var unreadable = 0
        var stored = 0
        repeat(LOCK_RACE_TRIALS) {
            val realKey = randomBytes(KEY_BYTES)
            val store = tempStore()
            val session = VaultSession.withVaultKey(VaultApi(UNUSED_SERVER), store, realKey)
            val started = CountDownLatch(1)
            val adder = addFilesUntilLocked(session, started)
            started.await()
            spin(Random.nextInt(MAX_SPIN_BEFORE_LOCK))
            session.lock()
            adder.join()
            store.all().forEach { record ->
                stored++
                if (runCatching { openMeta(realKey, record.sealedMeta) }.isFailure) unreadable++
            }
        }
        assertEquals(0, unreadable, "records sealed with a zeroed key out of $stored stored")
    }
}
