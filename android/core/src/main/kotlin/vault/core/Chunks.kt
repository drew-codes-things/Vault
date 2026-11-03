package vault.core

import java.io.IOException
import java.io.InputStream

private const val READ_AHEAD_CHUNKS = 4

fun interface PlainSource {
    fun open(skipBytes: Long): InputStream
}

fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        if (read() < 0) throw IOException("source ended while skipping")
        remaining--
    }
}

private fun InputStream.readExactly(buffer: ByteArray, length: Int) {
    var filled = 0
    while (filled < length) {
        val count = read(buffer, filled, length - filled)
        if (count < 0) throw IOException("source is shorter than its declared size")
        filled += count
    }
}

fun sealStream(fileKey: ByteArray, plainSize: Long, source: PlainSource, startSealedOffset: Long, sink: (ByteArray) -> Unit) {
    val totalChunks = chunkCount(plainSize)
    val firstChunk = startSealedOffset / SEALED_CHUNK_BYTES
    var trim = (startSealedOffset % SEALED_CHUNK_BYTES).toInt()
    if (firstChunk >= totalChunks) return
    source.open(firstChunk * PLAIN_CHUNK_BYTES).use { input ->
        val buffer = ByteArray(PLAIN_CHUNK_BYTES)
        for (index in firstChunk until totalChunks) {
            val length = minOf(PLAIN_CHUNK_BYTES.toLong(), plainSize - index * PLAIN_CHUNK_BYTES).toInt()
            input.readExactly(buffer, length)
            val sealed = sealChunk(fileKey, index, index == totalChunks - 1, buffer, length)
            sink(if (trim > 0) sealed.copyOfRange(trim, sealed.size) else sealed)
            trim = 0
        }
    }
}

class ChunkReader(
    private val fileKey: ByteArray,
    val plainSize: Long,
    private val fetch: (startInclusive: Long, endInclusive: Long) -> ByteArray,
) {
    private val totalChunks = chunkCount(plainSize)
    private val cache = HashMap<Long, ByteArray>()

    @Synchronized
    fun read(plainOffset: Long, length: Int): ByteArray {
        if (plainOffset < 0 || plainOffset >= plainSize || length <= 0) return ByteArray(0)
        val wanted = minOf(length.toLong(), plainSize - plainOffset).toInt()
        val output = ByteArray(wanted)
        var written = 0
        var position = plainOffset
        while (written < wanted) {
            val chunk = chunkAt(position / PLAIN_CHUNK_BYTES)
            val inChunk = (position % PLAIN_CHUNK_BYTES).toInt()
            val count = minOf(chunk.size - inChunk, wanted - written)
            System.arraycopy(chunk, inChunk, output, written, count)
            written += count
            position += count
        }
        return output
    }

    private fun chunkAt(index: Long): ByteArray {
        cache[index]?.let { return it }
        cache.clear()
        val lastIndex = minOf(index + READ_AHEAD_CHUNKS - 1, totalChunks - 1)
        val startByte = index * SEALED_CHUNK_BYTES
        val endByte = minOf((lastIndex + 1) * SEALED_CHUNK_BYTES, sealedSize(plainSize)) - 1
        val sealed = fetch(startByte, endByte)
        if (sealed.size.toLong() != endByte - startByte + 1) throw IOException("server returned a truncated range")
        var offset = 0
        for (chunkIndex in index..lastIndex) {
            val size = minOf(SEALED_CHUNK_BYTES.toLong(), sealedSize(plainSize) - chunkIndex * SEALED_CHUNK_BYTES).toInt()
            val piece = sealed.copyOfRange(offset, offset + size)
            cache[chunkIndex] = openChunk(fileKey, chunkIndex, chunkIndex == totalChunks - 1, piece)
            offset += size
        }
        return cache.getValue(index)
    }
}
