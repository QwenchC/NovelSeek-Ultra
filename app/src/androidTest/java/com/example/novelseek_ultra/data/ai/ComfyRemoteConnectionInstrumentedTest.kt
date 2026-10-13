package com.example.novelseek_ultra.data.ai

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live Android checks. A verified fingerprint must be supplied outside the network. */
@RunWith(AndroidJUnit4::class)
class ComfyRemoteConnectionInstrumentedTest {
    private fun verifiedEndpoint(): Pair<String, String> {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("comfyOrigin").orEmpty()
        val fingerprint = args.getString("comfyVerifiedSha256").orEmpty().lowercase()
        assumeTrue("Supply an HTTPS endpoint and independently verified SHA-256", origin.startsWith("https://") && fingerprint.matches(Regex("[a-f0-9]{64}")))
        return origin to fingerprint
    }

    @Test
    fun explicitVerifiedCertificateConnectsAndRevocationRestoresDefaultRejection() = runBlocking {
        val (origin, fingerprint) = verifiedEndpoint()
        val ai = AiService()
        val observed = withTimeout(20_000) { ai.inspectComfyCertificate(origin) }
        assertEquals("Observed certificate differs from independently verified PC certificate", fingerprint, observed.certificateSha256)
        assertEquals(ComfyTlsTrust.originFor(origin), observed.origin)
        val unapproved = withTimeout(75_000) { ai.diagnoseComfyUIConnection(origin) }
        assertFalse("Test expects an otherwise untrusted self-signed tunnel", unapproved.success)
        val revision = ai.currentComfyTrustRevision()
        val approved = withTimeout(75_000) { ai.diagnoseComfyUIConnection(origin, observed.toTrust(), revision) }
        assertTrue(approved.message, approved.success)
        ai.invalidateComfyCertificateTrust()
        val stale = withTimeout(10_000) { ai.diagnoseComfyUIConnection(origin, observed.toTrust(), revision) }
        assertFalse("An old grant must not revive a revoked certificate client", stale.success)
        assertFalse(withTimeout(75_000) { ai.diagnoseComfyUIConnection(origin) }.success)
    }

    @Test
    fun optInSmallImageCompletesSubmitPollDownloadThroughPinnedTls() = runBlocking {
        assumeTrue("Generation is opt-in because it consumes server GPU resources", InstrumentationRegistry.getArguments().getString("comfyGenerate") == "true")
        val (origin, fingerprint) = verifiedEndpoint()
        val ai = AiService()
        val observed = withTimeout(20_000) { ai.inspectComfyCertificate(origin) }
        assertEquals(fingerprint, observed.certificateSha256)
        val bytes = withTimeout(330_000) {
            ai.generateImageComfyUI(
                prompt = "A small blue ceramic cup on a plain white table, simple studio photograph, no text",
                width = 256, height = 256, baseUrl = origin,
                trustedCertificate = observed.toTrust(), expectedTrustRevision = ai.currentComfyTrustRevision(),
            )
        }
        assertTrue("Empty image response", bytes.isNotEmpty())
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Downloaded bytes are not an Android-decodable image", bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(256, bitmap.height)
        bitmap.recycle()
        // Only a test artifact; do not save certificate consent or pictures into the user's book.
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "comfy-live-test.png").writeBytes(bytes)
    }
}
