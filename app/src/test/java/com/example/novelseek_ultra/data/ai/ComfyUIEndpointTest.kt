package com.example.novelseek_ultra.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ComfyUIEndpointTest {
    @Test
    fun tcpTunnelAndLanAddressesUseHttpWithoutAddingAnInternalPort() {
        assertEquals("http://tunnel.example:18188", ComfyUIEndpoint.normalize(" tunnel.example:18188 "))
        assertEquals("http://192.168.1.4:8188", ComfyUIEndpoint.normalize("192.168.1.4:8188"))
        assertEquals("http://tunnel.example", ComfyUIEndpoint.normalize("tunnel.example"))
    }

    @Test
    fun httpsAuthorityAndProxyPrefixArePreservedForEveryRoute() {
        val endpoint = ComfyUIEndpoint.parse(" HTTPS://Tunnel.Example:18443/proxy/comfy/ ")
        assertEquals("https://tunnel.example:18443/proxy/comfy", endpoint.normalizedUrl)
        assertEquals("https://tunnel.example:18443/proxy/comfy/system_stats", endpoint.route("system_stats").toString())
        assertEquals("https://tunnel.example:18443/proxy/comfy/prompt", endpoint.route("prompt").toString())
        assertEquals("https://tunnel.example:18443/proxy/comfy/history/task-1", endpoint.route("history", "task-1").toString())
        assertEquals("/proxy/comfy/view", endpoint.view("image.png", "", "output").encodedPath)
    }

    @Test
    fun missingTrailingSlashDoesNotReplaceFinalProxyPathSegment() {
        val endpoint = ComfyUIEndpoint.parse("https://tunnel.example/comfy")
        assertEquals("/comfy/prompt", endpoint.route("prompt").encodedPath)
    }

    @Test
    fun bracketedIpv6HostAndPortAreSupported() {
        assertEquals("http://[2001:db8::1]:18188", ComfyUIEndpoint.normalize("[2001:db8::1]:18188"))
    }

    @Test
    fun pathAndImageQueryValuesCannotReplaceAuthorityOrEscapeProxyPrefix() {
        val endpoint = ComfyUIEndpoint.parse("https://tunnel.example/comfy")
        val history = endpoint.route("history", "task/with?special#value")
        assertEquals("tunnel.example", history.host)
        assertEquals("/comfy/history/task%2Fwith%3Fspecial%23value", history.encodedPath)
        val view = endpoint.view("封面 a&b?.png", "卷一/章节 #1", "output")
        assertEquals("/comfy/view", view.encodedPath)
        assertEquals("封面 a&b?.png", view.queryParameter("filename"))
        assertEquals("卷一/章节 #1", view.queryParameter("subfolder"))
        assertEquals("output", view.queryParameter("type"))
    }

    @Test
    fun invalidDotSegmentIdentifiersAreRejected() {
        val endpoint = ComfyUIEndpoint.parse("https://tunnel.example/comfy")
        listOf("", ".", "..").forEach { id ->
            assertTrue(runCatching { endpoint.route("history", id) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun unsupportedAddressesFailClearlyWithoutEchoingCredentialsOrTokens() {
        listOf(
            "", "ftp://tunnel.example", "http://user:secret@tunnel.example:8188",
            "http://@tunnel.example", "https://tunnel.example/?token=secret", "https://tunnel.example/?",
            "https://tunnel.example/#secret", "https://tunnel.example/#", "http://tunnel.example:99999",
            "https://tunnel.example\\proxy", "http://two hosts:8188", "http:///tunnel.example",
        ).forEach { address ->
            val failure = runCatching { ComfyUIEndpoint.normalize(address) }.exceptionOrNull()
            assertTrue("Expected invalid address: $address", failure is IllegalArgumentException)
            assertFalse(failure?.message.orEmpty().contains("secret"))
            assertTrue(failure?.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun systemStatsRequiresComfyIdentityRatherThanHttpSuccessOrGenericJson() {
        assertTrue(isComfyUISystemStats("""{"system":{"os":"win32","python_version":"3.12"},"devices":[]}"""))
        listOf(
            "<html>tunnel login</html>", "{}", "[]", "{broken", "{\"system\":{},\"devices\":[]}",
            "{\"system\":{\"os\":\"win32\",\"python_version\":\"3.12\"},\"devices\":{}}",
            "{\"system\":{\"os\":false,\"python_version\":\"3.12\"},\"devices\":[]}",
        ).forEach { assertFalse(isComfyUISystemStats(it)) }
    }

    @Test
    fun networkFailuresHaveSpecificSafeInstructions() {
        assertTrue(comfyUIConnectionFailureMessage(UnknownHostException("secret")).contains("DNS"))
        assertTrue(comfyUIConnectionFailureMessage(ConnectException("secret")).contains("端口"))
        assertTrue(comfyUIConnectionFailureMessage(InterruptedIOException("secret")).contains("超时"))
        assertTrue(comfyUIConnectionFailureMessage(SSLHandshakeException("secret")).contains("证书"))
        listOf(401, 403, 404, 502, 503, 504).forEach { code ->
            val message = comfyUIConnectionFailureMessage(ComfyUIHttpException(code, "secret"))
            assertTrue(message.contains("HTTP $code"))
            assertFalse(message.contains("secret"))
        }
    }

    @Test
    fun automaticHttpsLandingRequiresTheSpecificTunnelMarkerAndPreservesExternalPort() {
        assertTrue(isComfyUIAutomaticHttpsLanding("<html>您已为本隧道启用 <strong>自动&nbsp;HTTPS</strong></html>"))
        assertFalse(isComfyUIAutomaticHttpsLanding("<html>501 Not Implemented</html>"))
        assertFalse(isComfyUIAutomaticHttpsLanding("<html>Please enable HTTPS</html>"))
        val message = comfyUIConnectionFailureMessage(
            ComfyUIHttpException(501, "do not expose gateway HTML", automaticHttpsRequired = true),
            "http://tunnel.example:35961/proxy/comfy",
        )
        assertTrue(message.contains("https://tunnel.example:35961/proxy/comfy"))
        assertTrue(message.contains("自动 HTTPS"))
        assertFalse(message.contains("do not expose gateway HTML"))
        assertFalse(comfyUIConnectionFailureMessage(ComfyUIHttpException(501, "generic failure")).contains("自动 HTTPS"))
    }
}
