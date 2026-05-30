package com.example.novelseek_ultra.data.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Microsoft Edge "Read Aloud" online TTS over WebSocket — the same free neural voices the Edge
 * browser uses (NO subscription key). One [synthesize] call streams one text segment and returns
 * its full MP3 bytes; the audiobook player pipelines short segments so playback starts almost
 * immediately and feels streamed.
 *
 * NOTE: this is an UNOFFICIAL endpoint (it can change/break without notice). Errors surface to the
 * caller so the UI can show them. The `Sec-MS-GEC` token below replicates edge-tts's DRM scheme;
 * without it the endpoint returns 403.
 */
class EdgeTtsService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * @param text         plain text of one segment (a few sentences)
     * @param voice        neural voice short name, e.g. "zh-CN-XiaoxiaoNeural"
     * @param ratePercent  speed delta in percent, e.g. 0 (normal), 20 (faster), -20 (slower)
     */
    suspend fun synthesize(text: String, voice: String, ratePercent: Int): ByteArray =
        suspendCancellableCoroutine { cont ->
            val sec = generateSecMsGec()
            val url = "$WSS_URL&Sec-MS-GEC=$sec&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION" +
                "&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Origin", ORIGIN)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val audio = ByteArrayOutputStream()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(speechConfigMessage())
                    webSocket.send(ssmlMessage(text, voice, ratePercent))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Text frames carry control metadata; "Path:turn.end" marks the end of audio.
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        if (cont.isActive) cont.resume(audio.toByteArray())
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Binary frame layout: [2-byte big-endian header length][header text][audio bytes].
                    val b = bytes.toByteArray()
                    if (b.size < 2) return
                    val headerLen = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
                    val audioStart = 2 + headerLen
                    if (audioStart in 0 until b.size) audio.write(b, audioStart, b.size - audioStart)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) {
                        val code = response?.code
                        cont.resumeWithException(
                            if (code != null) RuntimeException("Edge TTS HTTP $code: ${t.message}") else t
                        )
                    }
                }
            })
            cont.invokeOnCancellation { runCatching { ws.cancel() } }
        }

    private fun speechConfigMessage(): String {
        val json = """{"context":{"synthesis":{"audio":{"metadataoptions":{""" +
            """"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
            """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
        return "X-Timestamp:${nowString()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" + json
    }

    private fun ssmlMessage(text: String, voice: String, ratePercent: Int): String {
        val rate = (if (ratePercent >= 0) "+" else "") + ratePercent + "%"
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$voice'><prosody rate='$rate' pitch='+0Hz'>${escapeXml(text)}</prosody></voice></speak>"
        return "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${nowString()}\r\n" +
            "Path:ssml\r\n\r\n" + ssml
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun nowString(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    /**
     * Replicates edge-tts's `Sec-MS-GEC` DRM token: round the current Windows-epoch time down to a
     * 5-minute window, scale to 100-ns ticks, then SHA-256 of "<ticks><token>" uppercased. The
     * float arithmetic mirrors the reference implementation so the resulting string matches.
     */
    private fun generateSecMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000.0
        ticks += 11644473600.0          // Unix epoch → Windows epoch (seconds)
        ticks -= ticks % 300.0          // round down to a 5-minute window
        ticks *= 1e7                    // seconds → 100-nanosecond intervals
        val input = String.format(Locale.US, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xff
                append("0123456789ABCDEF"[v ushr 4]); append("0123456789ABCDEF"[v and 0x0f])
            }
        }
    }

    companion object {
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        // Must track the Chromium version edge-tts currently spoofs, or the endpoint returns 403.
        private const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

        /** A small curated set of common Microsoft Chinese voices for the listen screen. */
        val VOICES: List<Pair<String, String>> = listOf(
            "zh-CN-XiaoxiaoNeural" to "晓晓（女声）",
            "zh-CN-YunxiNeural" to "云希（男声）",
            "zh-CN-YunyangNeural" to "云扬（男声·播音）",
            "zh-CN-XiaoyiNeural" to "晓伊（女声）",
            "zh-CN-YunjianNeural" to "云健（男声·浑厚）",
            "zh-CN-liaoning-XiaobeiNeural" to "晓北（东北女声）",
        )
    }
}
