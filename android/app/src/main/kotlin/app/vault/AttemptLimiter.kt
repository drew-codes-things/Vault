package app.vault

import android.content.SharedPreferences

private const val FREE_ATTEMPTS = 5
private const val BASE_LOCKOUT_MS = 30_000L
private const val MAX_LOCKOUT_MS = 15 * 60_000L

class AttemptLimiter(private val preferences: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    fun remainingLockoutMs(): Long = (preferences.getLong("lockedUntil", 0) - now()).coerceAtLeast(0)

    fun recordFailure() {
        val failures = preferences.getInt("failures", 0) + 1
        val editor = preferences.edit().putInt("failures", failures)
        if (failures >= FREE_ATTEMPTS) {
            val lockout = (BASE_LOCKOUT_MS shl (failures - FREE_ATTEMPTS).coerceAtMost(10)).coerceAtMost(MAX_LOCKOUT_MS)
            editor.putLong("lockedUntil", now() + lockout)
        }
        editor.apply()
    }

    fun recordSuccess() {
        preferences.edit().remove("failures").remove("lockedUntil").apply()
    }
}
