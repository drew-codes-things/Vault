package vault.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

private val fastParams = KdfParams(ByteArray(16) { it.toByte() }, 1, 64, 1)

private fun sealAll(key: ByteArray, plain: ByteArray, start: Long = 0): ByteArray {
    val out = ByteArrayOutputStream()
    sealStream(key, plain.size.toLong(), { skip -> ByteArrayInputStream(plain, skip.toInt(), plain.size - skip.toInt()) }, start) { out.write(it) }
    return out.toByteArray()
}

private fun readerFor(key: ByteArray, plainSize: Long, sealed: ByteArray, fetches: MutableList<LongRange>? = null) =
    ChunkReader(key, plainSize) { start, end ->
        fetches?.add(start..end)
        sealed.copyOfRange(start.toInt(), (end + 1).toInt())
    }

class CryptoTest {
    private val key = randomBytes(KEY_BYTES)

    @Test
    fun kdfIsDeterministicAndSplitsKeys() {
        val first = deriveKeys("correct horse".toCharArray(), fastParams)
        val second = deriveKeys("correct horse".toCharArray(), fastParams)
        val other = deriveKeys("correct horsf".toCharArray(), fastParams)
        assertContentEquals(first.masterKey, second.masterKey)
        assertContentEquals(first.authKey, second.authKey)
        assertNotEquals(first.masterKey.toList(), first.authKey.toList())
        assertNotEquals(first.masterKey.toList(), other.masterKey.toList())
    }

    @Test
    fun vaultKeyWrapRejectsWrongMasterKey() {
        val master = randomBytes(KEY_BYTES)
        val vault = randomBytes(KEY_BYTES)
        val wrapped = wrapVaultKey(master, vault)
        assertContentEquals(vault, unwrapVaultKey(master, wrapped))
        assertFailsWith<DecryptionException> { unwrapVaultKey(randomBytes(KEY_BYTES), wrapped) }
    }

    @Test
    fun metaRoundTripsAndRejectsTampering() {
        val vault = randomBytes(KEY_BYTES)
        val meta = FileMeta("holiday ✈.mp4", "video/mp4", 123456789012L, 1_700_000_000_000L, randomBytes(KEY_BYTES))
        val sealed = sealMeta(vault, meta)
        val opened = openMeta(vault, sealed)
        assertEquals(meta.name, opened.name)
        assertEquals(meta.mimeType, opened.mimeType)
        assertEquals(meta.plainSize, opened.plainSize)
        assertEquals(MediaKind.VIDEO, opened.kind)
        assertContentEquals(meta.fileKey, opened.fileKey)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertFailsWith<DecryptionException> { openMeta(vault, sealed) }
    }

    @Test
    fun thumbnailCannotBeOpenedAsMetadata() {
        val sealed = sealThumb(key, "thumb".toByteArray())
        assertFailsWith<DecryptionException> { openMeta(key, sealed) }
    }

    @Test
    fun roundTripsAtChunkBoundaries() {
        for (size in listOf(0, 1, PLAIN_CHUNK_BYTES - 1, PLAIN_CHUNK_BYTES, PLAIN_CHUNK_BYTES + 1, 3 * PLAIN_CHUNK_BYTES, 5 * PLAIN_CHUNK_BYTES + 777)) {
            val plain = Random(size).nextBytes(size)
            val sealed = sealAll(key, plain)
            assertEquals(sealedSize(size.toLong()), sealed.size.toLong(), "sealed size for $size")
            val reader = readerFor(key, size.toLong(), sealed)
            assertContentEquals(plain, reader.read(0, size + 10), "round trip for $size")
        }
    }

    @Test
    fun resumedSealingMatchesTheTailOfAFullSeal() {
        val plain = Random(1).nextBytes(3 * PLAIN_CHUNK_BYTES + 500)
        val full = sealAll(key, plain)
        for (start in listOf(0L, 5L, SEALED_CHUNK_BYTES.toLong(), SEALED_CHUNK_BYTES + 12345L, full.size - 1L)) {
            assertContentEquals(full.copyOfRange(start.toInt(), full.size), sealAll(key, plain, start), "resume from $start")
        }
    }

    @Test
    fun randomAccessReadsMatchPlaintextAndBatchFetches() {
        val plain = Random(2).nextBytes(10 * PLAIN_CHUNK_BYTES + 321)
        val sealed = sealAll(key, plain)
        val fetches = mutableListOf<LongRange>()
        val reader = readerFor(key, plain.size.toLong(), sealed, fetches)
        val offsets = listOf(0L, 100L, PLAIN_CHUNK_BYTES - 5L, 7L * PLAIN_CHUNK_BYTES + 99, plain.size - 10L)
        for (offset in offsets) {
            val length = 4096
            val expected = plain.copyOfRange(offset.toInt(), minOf(plain.size, offset.toInt() + length))
            assertContentEquals(expected, reader.read(offset, length), "read at $offset")
        }
        assertContentEquals(plain.copyOfRange(PLAIN_CHUNK_BYTES - 10, PLAIN_CHUNK_BYTES + 10), reader.read(PLAIN_CHUNK_BYTES - 10L, 20))
        assertEquals(0, reader.read(plain.size.toLong(), 10).size)
        assert(fetches.all { it.last - it.first + 1 <= 4L * SEALED_CHUNK_BYTES })
    }

    @Test
    fun tamperedChunkIsRejected() {
        val plain = Random(3).nextBytes(2 * PLAIN_CHUNK_BYTES)
        val sealed = sealAll(key, plain)
        sealed[SEALED_CHUNK_BYTES + 10] = (sealed[SEALED_CHUNK_BYTES + 10].toInt() xor 1).toByte()
        val reader = readerFor(key, plain.size.toLong(), sealed)
        assertFailsWith<DecryptionException> { reader.read(PLAIN_CHUNK_BYTES.toLong(), 100) }
    }

    @Test
    fun reorderedChunksAreRejected() {
        val plain = Random(4).nextBytes(2 * PLAIN_CHUNK_BYTES)
        val sealed = sealAll(key, plain)
        val swapped = sealed.copyOfRange(SEALED_CHUNK_BYTES, 2 * SEALED_CHUNK_BYTES) + sealed.copyOfRange(0, SEALED_CHUNK_BYTES)
        val reader = readerFor(key, plain.size.toLong(), swapped)
        assertFailsWith<DecryptionException> { reader.read(0, 100) }
    }

    @Test
    fun truncatedFileIsRejectedEvenWhenChunkBoundaryIsClean() {
        val plain = Random(5).nextBytes(3 * PLAIN_CHUNK_BYTES)
        val sealed = sealAll(key, plain)
        val claimedSize = 2L * PLAIN_CHUNK_BYTES
        val truncated = sealed.copyOfRange(0, 2 * SEALED_CHUNK_BYTES)
        val reader = readerFor(key, claimedSize, truncated)
        assertFailsWith<DecryptionException> { reader.read(PLAIN_CHUNK_BYTES.toLong(), 100) }
    }

    @Test
    fun wrongKeyIsRejected() {
        val plain = Random(6).nextBytes(1000)
        val sealed = sealAll(key, plain)
        val reader = readerFor(randomBytes(KEY_BYTES), plain.size.toLong(), sealed)
        assertFailsWith<DecryptionException> { reader.read(0, 10) }
    }

    @Test
    fun shortSourceFailsLoudly() {
        val plain = ByteArray(1000)
        assertFailsWith<java.io.IOException> {
            sealStream(key, 2000, { skip -> ByteArrayInputStream(plain, skip.toInt(), plain.size - skip.toInt()) }, 0) {}
        }
    }

    @Test
    fun legacyMetadataWithoutFolderStillOpens() {
        val vault = randomBytes(KEY_BYTES)
        val fileKey = randomBytes(KEY_BYTES)
        val legacy = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(legacy).use { out ->
            out.writeByte(1)
            out.writeUTF("old.jpg")
            out.writeUTF("image/jpeg")
            out.writeLong(10)
            out.writeLong(5)
            out.writeInt(fileKey.size)
            out.write(fileKey)
        }
        val opened = openMeta(vault, sealBlob(vault, "meta-v1", legacy.toByteArray()))
        assertEquals("old.jpg", opened.name)
        assertEquals("", opened.folder)
        assertEquals("Us", openMeta(vault, sealMeta(vault, opened.inFolder("Us"))).folder)
    }
}
