package com.example.novelseek_ultra.data.ai

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight DuckDuckGo web search for the agent. Uses the keyless HTML endpoint
 * (`html.duckduckgo.com/html/`) and scrapes result titles + snippets + links.
 *
 * NOTE: unofficial endpoint — it can change/break (like Edge TTS). Failures return an empty list
 * and the agent simply proceeds without web context.
 */
class WebSearchService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Result(val title: String, val snippet: String, val url: String)

    suspend fun search(query: String, limit: Int = 5): List<Result> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .post(FormBody.Builder().add("q", query).build())
                .build()
            val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            parse(html, limit)
        }.getOrDefault(emptyList())
    }

    private fun parse(html: String, limit: Int): List<Result> {
        val results = mutableListOf<Result>()
        // Each result block: <a ... class="result__a" href="URL">TITLE</a> ... <a class="result__snippet">SNIPPET</a>
        val linkRe = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRe = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).toList()
        for (i in links.indices) {
            if (results.size >= limit) break
            val rawUrl = links[i].groupValues[1]
            val title = stripHtml(links[i].groupValues[2])
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.let { stripHtml(it) }.orEmpty()
            if (title.isNotBlank()) results += Result(title, snippet, decodeDdgUrl(rawUrl))
        }
        return results
    }

    /** DDG html results wrap the real URL in a `uddg=` redirect param; unwrap it when present. */
    private fun decodeDdgUrl(url: String): String {
        val marker = "uddg="
        val idx = url.indexOf(marker)
        if (idx < 0) return url
        val enc = url.substring(idx + marker.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(enc, "UTF-8") }.getOrDefault(url)
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .trim()
}
