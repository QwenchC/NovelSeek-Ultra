package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.EmbeddingConfig
import com.example.novelseek_ultra.data.model.TextModelConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/**
 * Security boundary shared by every user-configurable text/embedding endpoint.
 *
 * Cloud traffic must use HTTPS. Plain HTTP is accepted only for an explicitly local endpoint:
 * loopback may carry a key (the traffic never leaves the device), while LAN/private endpoints are
 * accepted only without a key so a reusable credential cannot be exposed to the local network.
 * ComfyUI uses a separate, keyless image path and is intentionally outside this policy.
 */
object ApiEndpointPolicy {

    data class Assessment(
        val allowed: Boolean,
        val message: String? = null,
        val isLocal: Boolean = false,
    )

    fun assess(baseUrl: String, apiKey: String): Assessment {
        val url = baseUrl.trim().toHttpUrlOrNull()
            ?: return rejected("API URL 无效 / Invalid API URL")
        return when (url.scheme.lowercase()) {
            "https" -> Assessment(allowed = true)
            "http" -> {
                val host = url.host.lowercase().removeSuffix(".")
                when {
                    isLoopback(host) -> Assessment(allowed = true, isLocal = true)
                    isPrivateOrLinkLocal(host) && apiKey.isBlank() ->
                        Assessment(allowed = true, isLocal = true)
                    isPrivateOrLinkLocal(host) -> rejected(
                        "局域网 HTTP 不能携带 API Key；请改用 HTTPS，或清空仅供本地服务使用的 Key / " +
                            "LAN HTTP cannot carry an API key; use HTTPS or clear the local-only key",
                        isLocal = true,
                    )
                    else -> rejected(
                        "远程 API 必须使用 HTTPS / Remote APIs must use HTTPS",
                    )
                }
            }
            else -> rejected("API URL 仅支持 HTTPS 或本地 HTTP / Only HTTPS or local HTTP is supported")
        }
    }

    fun requireAllowed(baseUrl: String, apiKey: String) {
        val assessment = assess(baseUrl, apiKey)
        if (!assessment.allowed) throw IOException(assessment.message ?: "Unsafe API endpoint")
    }

    private fun rejected(message: String, isLocal: Boolean = false) =
        Assessment(allowed = false, message = message, isLocal = isLocal)

    private fun isLoopback(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1") return true
        val ipv4 = parseIpv4(host) ?: return false
        return ipv4[0] == 127
    }

    private fun isPrivateOrLinkLocal(host: String): Boolean {
        if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
            host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
        ) return host.contains(':')

        val ipv4 = parseIpv4(host) ?: return false
        return ipv4[0] == 10 ||
            (ipv4[0] == 172 && ipv4[1] in 16..31) ||
            (ipv4[0] == 192 && ipv4[1] == 168) ||
            (ipv4[0] == 169 && ipv4[1] == 254)
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || (part.length > 1 && part.startsWith('0'))) return null
            out[index] = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return out
    }
}

fun TextModelConfig.isUsableApiConfig(): Boolean {
    val endpoint = ApiEndpointPolicy.assess(apiUrl, apiKey)
    return apiUrl.isNotBlank() && model.isNotBlank() && endpoint.allowed &&
        (apiKey.isNotBlank() || endpoint.isLocal)
}

fun EmbeddingConfig.isUsableApiConfig(): Boolean {
    val endpoint = ApiEndpointPolicy.assess(apiUrl, apiKey)
    return apiUrl.isNotBlank() && model.isNotBlank() && endpoint.allowed &&
        (apiKey.isNotBlank() || endpoint.isLocal)
}
