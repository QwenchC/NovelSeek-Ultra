package com.example.novelseek_ultra.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A connection check identifies the API, not availability of the configured image models. */
data class ComfyUIConnectionResult(
    val success: Boolean,
    val message: String,
    val normalizedUrl: String? = null,
)

/** Preserve the user-selected tunnel authority and reverse-proxy prefix for every API route. */
class ComfyUIEndpoint private constructor(private val base: HttpUrl) {
    val normalizedUrl: String = base.toString().removeSuffix("/")

    internal fun route(vararg segments: String): HttpUrl {
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "ComfyUI 返回了无效的 API 路径标识。(Invalid ComfyUI API path identifier.)"
        }
        return base.newBuilder().apply { segments.forEach(::addPathSegment) }.build()
    }

    internal fun view(filename: String, subfolder: String, type: String): HttpUrl = route("view")
        .newBuilder()
        .addQueryParameter("filename", filename)
        .addQueryParameter("subfolder", subfolder)
        .addQueryParameter("type", type)
        .build()

    companion object {
        /** Canonical, credential-free base address for explicit settings validation and saving. */
        fun normalize(baseUrl: String): String = parse(baseUrl).normalizedUrl

        /** Bare LAN IPs and TCP tunnel host:port values use HTTP; HTTPS must be explicit. */
        internal fun parse(input: String): ComfyUIEndpoint {
            val value = input.trim()
            require(value.isNotEmpty()) { "请输入 ComfyUI 地址。(Enter a ComfyUI address.)" }
            require(value.none { it.isWhitespace() || it == '\\' || it.isISOControl() }) {
                "ComfyUI 地址不能包含空白、控制字符或反斜杠。(Whitespace, control characters and backslashes are not supported.)"
            }
            val withScheme = if (value.contains("://")) {
                require(value.startsWith("http://", true) || value.startsWith("https://", true)) {
                    "ComfyUI 地址仅支持 http:// 或 https://。(Only HTTP and HTTPS are supported.)"
                }
                value
            } else {
                "http://$value"
            }
            val authority = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            require(authority.isNotBlank() && '@' !in authority) {
                "请填写不含用户名和密码的 ComfyUI 服务地址；暂不支持网关登录凭据。(Enter an address without credentials; gateway authentication is not supported.)"
            }
            val url = withScheme.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("ComfyUI 地址格式不正确，请填写完整 HTTP/HTTPS 地址或主机:端口。(Enter a valid HTTP/HTTPS URL or host:port.)")
            require(url.username.isEmpty() && url.password.isEmpty()) {
                "ComfyUI 地址不能包含用户名或密码。(URL credentials are not supported.)"
            }
            require(url.query == null && url.fragment == null) {
                "请填写 ComfyUI 服务基址，可保留反向代理路径，但不能包含查询参数或 # 片段。(Enter the service base URL, optionally with a proxy path, without a query or fragment.)"
            }
            return ComfyUIEndpoint(url)
        }
    }
}

/** Official /system_stats returns a system identity object and a devices list (also on CPU). */
internal fun isComfyUISystemStats(body: String): Boolean {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return false
    val system = root["system"] as? JsonObject ?: return false
    if (root["devices"] !is JsonArray) return false
    return listOf("os", "python_version").all { key ->
        (system[key] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true
    }
}

internal class ComfyUIHttpException(
    val statusCode: Int,
    message: String,
    val automaticHttpsRequired: Boolean = false,
) : IOException(message)

/** Sakura's automatic-HTTPS HTTP landing page is a protocol hint, not a ComfyUI API error. */
internal fun isComfyUIAutomaticHttpsLanding(body: String): Boolean {
    val text = body.replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace('\u00a0', ' ')
    return text.contains("您已为本隧道启用") &&
        Regex("自动\\s*HTTPS", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

/** Never show an HTML gateway body or URL credentials in the connection diagnosis. */
internal fun comfyUIConnectionFailureMessage(error: Exception, normalizedUrl: String? = null): String = when (error) {
    is IllegalArgumentException -> error.message ?: "ComfyUI 地址格式不正确。"
    is ComfyUIHttpException -> when (error.statusCode) {
        401, 403 -> "HTTP ${error.statusCode}：隧道或网关要求登录/拒绝访问；当前连接不支持网关凭据，请使用允许该设备访问的 API 地址。"
        404 -> "HTTP 404：未找到 /system_stats，请检查 ComfyUI 基址和反向代理路径，不要填写具体网页或 API 路由。"
        502, 503, 504 -> "HTTP ${error.statusCode}：隧道/网关未能连接上游服务，请检查隧道是否在线、本地端口和 ComfyUI 是否启动。"
        501 -> if (error.automaticHttpsRequired) {
            val httpsAddress = normalizedUrl?.toHttpUrlOrNull()?.newBuilder()?.scheme("https")?.build()
                ?.toString()?.removeSuffix("/")
            "HTTP 501：该隧道已启用“自动 HTTPS”，请将地址改为 " +
                (httpsAddress ?: "https:// 开头的同域名、端口和路径") +
                " 后重新测试；如遇证书不受信任，请修复隧道证书，不会绕过 TLS 校验。"
        } else {
            "ComfyUI 返回 HTTP 501，请检查服务和隧道配置。"
        }
        in 300..399 -> "HTTP ${error.statusCode}：服务要求跳转，请直接填写最终 HTTP/HTTPS 基址；不会自动降低 HTTPS 安全级别。"
        else -> "ComfyUI 返回 HTTP ${error.statusCode}，请检查服务和隧道配置。"
    }
    is UnknownHostException -> "无法解析主机名，请检查公网域名、手机网络和 DNS。"
    is SSLException -> "HTTPS/TLS 连接失败，请检查地址协议、域名和有效证书；不会绕过证书校验。"
    is ConnectException -> "无法连接服务器，请检查公网主机/端口、隧道在线状态和本地 ComfyUI 端口映射。"
    is InterruptedIOException -> "连接超时，请检查手机网络、隧道在线状态和 HTTP/HTTPS 协议是否与隧道一致。"
    else -> "连接失败，请检查手机网络、ComfyUI 服务和公网隧道设置。"
}
