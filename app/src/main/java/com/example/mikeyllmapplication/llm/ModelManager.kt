package com.example.mikeyllmapplication.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Long,
        /** Human-readable status while not actively receiving bytes (connecting, retrying...). */
        val status: String? = null,
    ) : DownloadState {
        val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data class Failed(val message: String) : DownloadState
}

/**
 * Finds model files on disk and downloads missing ones (resumable, so a dropped connection
 * does not restart a 2.5 GB download from zero).
 *
 * Model files are looked up in, in order:
 *  1. /sdcard/Android/data/<package>/files/models/   (download target, also writable with `adb push`)
 *  2. /data/local/tmp/llm/                             (the location used by Google's LLM samples)
 */
class ModelManager(context: Context) {

    private val app = context.applicationContext

    val downloadDir: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "models").apply { mkdirs() }
    private val sideloadDirs = listOf(downloadDir, File("/data/local/tmp/llm"))

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    fun findModelFile(spec: ModelSpec): File? = sideloadDirs
        .map { File(it, spec.fileName) }
        .firstOrNull { it.isFile && it.canRead() && isComplete(spec, it) }

    fun isInstalled(spec: ModelSpec) = findModelFile(spec) != null

    /** Accept any file at least as large as expected (a newer upload may be slightly bigger). */
    private fun isComplete(spec: ModelSpec, f: File) = f.length() >= spec.sizeBytes * 98 / 100

    private fun partFile(spec: ModelSpec) = File(downloadDir, spec.fileName + ".part")

    fun partialBytes(spec: ModelSpec): Long = partFile(spec).takeIf { it.exists() }?.length() ?: 0

    fun delete(spec: ModelSpec): Boolean {
        partFile(spec).delete()
        return File(downloadDir, spec.fileName).let { !it.exists() || it.delete() }
    }

    private fun setState(id: String, s: DownloadState) = _downloads.update { it + (id to s) }

    /**
     * Downloads [spec] into [downloadDir]. Cancelling the calling coroutine pauses the download;
     * calling again resumes from where it stopped. [hfToken] is only needed for gated repos.
     */
    suspend fun download(spec: ModelSpec, hfToken: String?): Result<File> = withContext(Dispatchers.IO) {
        val target = File(downloadDir, spec.fileName)
        val part = partFile(spec)
        try {
            var attempt = 0
            while (true) {
                setState(spec.id, DownloadState.Running(partialBytes(spec), spec.sizeBytes, 0, "Connecting…"))
                try {
                    downloadOnce(spec, part, hfToken)
                    break
                } catch (e: IOException) {
                    // Transient network errors: retry with backoff, resuming from the partial file.
                    if (e is HttpStatusException && e.code in 400..499) throw e
                    if (++attempt >= MAX_ATTEMPTS) throw e
                    val wait = 2L * attempt
                    setState(
                        spec.id,
                        DownloadState.Running(
                            partialBytes(spec), spec.sizeBytes, 0,
                            "${describe(e)} — retrying in $wait s (attempt ${attempt + 1}/$MAX_ATTEMPTS)",
                        ),
                    )
                    kotlinx.coroutines.delay(wait * 1000)
                }
            }
            if (!part.renameTo(target)) throw IOException("could not move ${part.name} into place")
            setState(spec.id, DownloadState.Idle)
            Result.success(target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            setState(spec.id, DownloadState.Idle)
            throw e
        } catch (e: Throwable) {
            val msg = when {
                e is HttpStatusException && (e.code == 401 || e.code == 403) ->
                    "This model is gated on Hugging Face (HTTP ${e.code}). Accept its licence on huggingface.co and " +
                        "enter a read token below, or push the file with adb."
                e is HttpStatusException -> "Server returned HTTP ${e.code}"
                else -> describe(e)
            }
            setState(spec.id, DownloadState.Failed(msg))
            Result.failure(e)
        }
    }

    private suspend fun downloadOnce(spec: ModelSpec, part: File, hfToken: String?) {
        val already = part.takeIf { it.exists() }?.length() ?: 0L
        val conn = openFollowingRedirects(URL(spec.url), already, hfToken)
        try {
            val code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL
            when {
                code == 416 && already > 0 -> return // already have everything
                code != HttpURLConnection.HTTP_OK && !append -> throw HttpStatusException(code)
            }
            val start = if (append) already else 0L
            val total = if (append) start + conn.contentLengthLong else conn.contentLengthLong
                .takeIf { it > 0 } ?: spec.sizeBytes
            val buf = ByteArray(1 shl 16)
            var done = start
            var windowStart = System.nanoTime()
            var windowBytes = 0L
            var speed = 0L
            setState(spec.id, DownloadState.Running(done, total, 0))
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        windowBytes += n
                        val elapsed = System.nanoTime() - windowStart
                        if (elapsed > 500_000_000L) {
                            speed = windowBytes * 1_000_000_000L / elapsed
                            windowStart = System.nanoTime()
                            windowBytes = 0
                            setState(spec.id, DownloadState.Running(done, total, speed))
                        }
                    }
                }
            }
            if (done < total) throw IOException("connection closed early ($done of $total bytes)")
        } finally {
            conn.disconnect()
        }
    }

    /** HttpURLConnection does not follow cross-host redirects (HF -> CDN) when headers matter, so do it by hand. */
    private fun openFollowingRedirects(start: URL, rangeFrom: Long, token: String?): HttpURLConnection {
        var url = start
        repeat(8) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "MikeyLLM-Android/1.0")
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
                // Only send the token to Hugging Face itself, never to the CDN it redirects to.
                if (!token.isNullOrBlank() && url.host.endsWith("huggingface.co")) {
                    setRequestProperty("Authorization", "Bearer ${token.trim()}")
                }
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                c.disconnect()
                url = URL(url, loc)
            } else {
                return c
            }
        }
        throw IOException("too many redirects")
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "No internet / DNS failure (cannot resolve ${e.message})"
        is java.net.SocketTimeoutException -> "Connection timed out"
        is javax.net.ssl.SSLException -> "TLS error: ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private companion object {
        const val MAX_ATTEMPTS = 6
    }
}
