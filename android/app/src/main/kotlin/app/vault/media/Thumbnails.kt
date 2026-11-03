package app.vault.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private const val THUMB_MAX_EDGE = 480
private const val THUMB_JPEG_QUALITY = 80
private const val VIDEO_FRAME_TIME_US = 1_000_000L

private fun Bitmap.toJpeg(): ByteArray {
    val output = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, output)
    return output.toByteArray()
}

private fun Bitmap.fitted(): Bitmap {
    val scale = THUMB_MAX_EDGE.toFloat() / max(width, height)
    if (scale >= 1f) return this
    return Bitmap.createScaledBitmap(this, (width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1), true)
}

fun imageThumbnail(context: Context, uri: Uri): ByteArray? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val scale = minOf(1f, THUMB_MAX_EDGE.toFloat() / max(info.size.width, info.size.height))
        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    bitmap.toJpeg()
}.getOrNull()

fun videoThumbnail(context: Context, uri: Uri): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val frame = retriever.getFrameAtTime(VIDEO_FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0)
        frame?.fitted()?.toJpeg()
    } catch (error: RuntimeException) {
        null
    } finally {
        retriever.release()
    }
}
