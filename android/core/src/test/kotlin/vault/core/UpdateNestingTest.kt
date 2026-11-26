package vault.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val NESTING_TOO_DEEP = 40

private val DEEP_OPENING = "[".repeat(NESTING_TOO_DEEP)
private val DEEP_CLOSING = "]".repeat(NESTING_TOO_DEEP)

private class NestingStore(override var checksEnabled: Boolean = true, override var dismissedVersion: String = "") : UpdateNoticeStore

class UpdateNestingTest {
    private fun answerWithTag(extra: String) = """{"tag_name":"v1.0"$extra}"""

    @Test
    fun aDeeplyNestedAnswerIsRefusedBeforeTheParserSeesIt() {
        val answer = answerWithTag(""","a":$DEEP_OPENING$DEEP_CLOSING""")
        assertFailsWith<UpdateCheckException> { releasedVersion(answer) }
    }

    @Test
    fun anAnswerThatNestsWithinTheLimitIsStillRead() {
        val shallow = "[".repeat(8) + "]".repeat(8)
        assertEquals("1.0", releasedVersion(answerWithTag(""","a":$shallow""")))
    }

    @Test
    fun aRealisticAnswerWithPunctuationInsideStringsIsStillRead() {
        val body = "\"it's [a](b) // c # d /* e */ \\\" \\\\ " + "{".repeat(50) + "\""
        val extra = ",\"body\":" + body + ",\"assets\":[{\"name\":\"x\"}]"
        assertEquals("1.0", releasedVersion(answerWithTag(extra)))
    }

    @Test
    fun answersThatHideTheDepthFromAScannerAreRefused() {
        val hidden = listOf(
            "{'\"':$DEEP_OPENING$DEEP_CLOSING,\"tag_name\":\"v1.0\"}",
            "{/*\"*/\"a\":$DEEP_OPENING$DEEP_CLOSING,\"tag_name\":\"v1.0\"}",
            "{#\"\n\"a\":$DEEP_OPENING$DEEP_CLOSING,\"tag_name\":\"v1.0\"}",
            "{\"a\":[t\",$DEEP_OPENING$DEEP_CLOSING],\"tag_name\":\"v1.0\"}",
        )
        for (answer in hidden) {
            assertFailsWith<UpdateCheckException>(answer) { releasedVersion(answer) }
        }
    }

    @Test
    fun aHugeNestingBombIsRefusedWithoutOverflowingTheStack() {
        val bomb = "{\"tag_name\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertFailsWith<UpdateCheckException> { releasedVersion(bomb) }
    }

    @Test
    fun anUnexpectedFailureInTheFetchIsLoggedNotThrown() {
        for (failure in listOf<() -> String>({ throw IllegalStateException("odd") }, { throw StackOverflowError() })) {
            val logged = mutableListOf<String>()
            val notice = UpdateNotice("1.4.0", NestingStore(), failure, { logged += it })
            assertNull(notice.checkIfDue())
            assertEquals(1, logged.size)
        }
    }
}
