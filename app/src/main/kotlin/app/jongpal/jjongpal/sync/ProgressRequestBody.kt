package app.jongpal.jjongpal.sync

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

/**
 * 업로드 진행률을 알려주는 요청 본문.
 * 파일을 조각내어 보내면서 보낸 양을 콜백으로 전달한다 → 알림 진행바에 사용.
 */
class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType
    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        val buf = ByteArray(CHUNK)
        var lastReported = 0L
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                sink.write(buf, 0, read)
                sent += read
                // 너무 잦은 알림 갱신을 막기 위해 1% 또는 512KB 단위로만 보고
                if (sent - lastReported >= REPORT_STEP || sent == total) {
                    lastReported = sent
                    onProgress(sent, total)
                }
            }
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val REPORT_STEP = 512L * 1024
    }
}
