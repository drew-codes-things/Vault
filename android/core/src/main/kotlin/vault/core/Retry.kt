package vault.core

import java.io.IOException

fun <T> retryIo(attempts: Int, baseDelayMs: Long, sleep: (Long) -> Unit = Thread::sleep, block: () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (error: IOException) {
            val permanent = error is ApiException && error.status in 400..499
            attempt++
            if (permanent || attempt >= attempts) throw error
            sleep(baseDelayMs shl (attempt - 1))
        }
    }
}
