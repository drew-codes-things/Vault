package app.vault.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import vault.core.ChunkReader
import java.io.IOException

@UnstableApi
class VaultDataSource(private val readers: (String) -> ChunkReader?) : BaseDataSource(false) {
    private var reader: ChunkReader? = null
    private var currentUri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val id = dataSpec.uri.host ?: throw IOException("missing file id")
        val source = readers(id) ?: throw IOException("file is not available")
        transferInitializing(dataSpec)
        if (dataSpec.position > source.plainSize) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        currentUri = dataSpec.uri
        reader = source
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else source.plainSize - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val source = reader ?: throw IOException("data source is closed")
        val bytes = source.read(position, minOf(length.toLong(), remaining).toInt())
        if (bytes.isEmpty()) return C.RESULT_END_OF_INPUT
        System.arraycopy(bytes, 0, buffer, offset, bytes.size)
        position += bytes.size
        remaining -= bytes.size
        bytesTransferred(bytes.size)
        return bytes.size
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        reader = null
        currentUri = null
    }

    class Factory(private val readers: (String) -> ChunkReader?) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(readers)
    }
}
