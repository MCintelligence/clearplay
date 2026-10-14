package app.clearplay

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Some apps save videos as ".mp4a", ".mp4b", ".mp5", ".vidy", ".luxy" or ".xvideo". These are
 * normal MP4 files with three header bytes shifted (offsets 3, 7 and 15 raised by 12, 4 and 2),
 * which turns the "ftyp" marker into "ftyt" so other players reject them as corrupted.
 * This reader shifts those bytes back on the fly. The file on disk is never changed.
 */
@OptIn(UnstableApi::class)
class ScrambledMp4DataSource(
    private val context: Context,
    private val upstream: DataSource
) : DataSource {

    private var position = 0L
    private var unscramble = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        unscramble = isScrambled(context, dataSpec.uri)
        position = dataSpec.position
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            if (unscramble && position < HEADER_SIZE) {
                for ((at, delta) in PATCHES) {
                    if (at >= position && at < position + n) {
                        val i = offset + (at - position).toInt()
                        buffer[i] = (buffer[i] + delta).toByte()
                    }
                }
            }
            position += n
        }
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val context: Context) : DataSource.Factory {
        private val defaultFactory = DefaultDataSource.Factory(context)
        override fun createDataSource(): DataSource =
            ScrambledMp4DataSource(context.applicationContext, defaultFactory.createDataSource())
    }

    companion object {
        private const val HEADER_SIZE = 16L
        private val PATCHES = listOf(3L to -12, 7L to -4, 15L to -2)
        private val EXTENSIONS = setOf("mp4a", "mp4b", "mp5", "vidy", "luxy", "xvideo")
        private val cache = ConcurrentHashMap<String, Boolean>()

        fun isScrambled(context: Context, uri: Uri): Boolean =
            cache.getOrPut(uri.toString()) { detect(context, uri) }

        private fun detect(context: Context, uri: Uri): Boolean {
            val header = readHeader(context, uri) ?: return false
            val box = String(header, 4, 4, Charsets.ISO_8859_1)
            if (box == "ftyt") return true   // the scrambled marker
            if (box == "ftyp") return false  // already a normal MP4, leave it alone
            return extensionOf(context, uri) in EXTENSIONS
        }

        private fun readHeader(context: Context, uri: Uri): ByteArray? = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(8)
                var read = 0
                while (read < buf.size) {
                    val k = input.read(buf, read, buf.size - read)
                    if (k < 0) break
                    read += k
                }
                if (read == buf.size) buf else null
            }
        }.getOrNull()

        private fun extensionOf(context: Context, uri: Uri): String {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: ""
            return name.substringAfterLast('.', "").lowercase()
        }
    }
}
