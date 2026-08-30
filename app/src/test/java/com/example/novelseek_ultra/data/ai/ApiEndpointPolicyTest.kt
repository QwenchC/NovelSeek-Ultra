package com.example.novelseek_ultra.data.ai

import com.example.novelseek_ultra.data.model.TextModelConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEndpointPolicyTest {

    @Test
    fun acceptsHttpsWithOrWithoutCredential() {
        assertTrue(ApiEndpointPolicy.assess("https://api.deepseek.com/v1", "secret").allowed)
        assertTrue(ApiEndpointPolicy.assess("https://example.com/v1", "").allowed)
    }

    @Test
    fun acceptsLoopbackHttpWithCredential() {
        assertTrue(ApiEndpointPolicy.assess("http://127.0.0.1:11434/v1", "local-key").allowed)
        assertTrue(ApiEndpointPolicy.assess("http://[::1]:11434/v1", "local-key").allowed)
        assertTrue(ApiEndpointPolicy.assess("http://localhost:11434/v1", "local-key").allowed)
    }

    @Test
    fun acceptsKeylessPrivateLanHttp() {
        assertTrue(ApiEndpointPolicy.assess("http://192.168.1.20:11434/v1", "").allowed)
        assertTrue(ApiEndpointPolicy.assess("http://10.0.2.2:11434/v1", "").allowed)
        assertFalse(
            "Unresolved hostnames can be DNS-rebound and must use HTTPS",
            ApiEndpointPolicy.assess("http://model-box.local:11434/v1", "").allowed,
        )
    }

    @Test
    fun rejectsCredentialOverLanHttp() {
        val result = ApiEndpointPolicy.assess("http://192.168.1.20:11434/v1", "real-key")
        assertFalse(result.allowed)
        assertTrue(result.message.orEmpty().contains("API Key"))
    }

    @Test
    fun rejectsRemoteHttpAndMalformedSchemes() {
        assertFalse(ApiEndpointPolicy.assess("http://api.example.com/v1", "").allowed)
        assertFalse(ApiEndpointPolicy.assess("ftp://example.com/v1", "").allowed)
        assertFalse(ApiEndpointPolicy.assess("not a url", "").allowed)
    }

    @Test
    fun readinessStillRequiresCredentialForCloudButNotLocalService() {
        val base = TextModelConfig(apiUrl = "https://api.example.com/v1", model = "model")
        assertFalse(base.isUsableApiConfig())
        assertTrue(base.copy(apiKey = "secret").isUsableApiConfig())
        assertTrue(base.copy(apiUrl = "http://127.0.0.1:11434/v1").isUsableApiConfig())
    }
}
