package com.example.novelseek_ultra.data

import com.example.novelseek_ultra.data.ai.ComfyCertificateTrust
import com.example.novelseek_ultra.data.ai.ComfyTlsTrust
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Offline persistence tests only; these mock certs confer no production trust or credentials. */
class ComfyCertificateTrustStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val localRoot: File get() = File(temporaryFolder.root, "no_backup/comfy_tls_trust")
    private val savedFile: File get() = File(localRoot, "certificates.json")

    @Test
    fun explicitApprovalPersistsAndCanBeReopened() {
        val trust = trustFor(ORIGIN)
        val store = ComfyCertificateTrustStore(localRoot)
        assertNull(store.find(ORIGIN))
        assertFalse(savedFile.exists())
        store.approve(trust)
        assertTrue(savedFile.isFile)
        assertEquals(trust, ComfyCertificateTrustStore(localRoot).find(ORIGIN))
        assertFalse(File(localRoot, "certificates.json.new").exists())
        assertFalse(File(localRoot, "certificates.json.bak").exists())
    }

    @Test
    fun proxyPathSharesOriginButHostPortAndSchemeNeverDo() {
        val trust = trustFor(ORIGIN)
        val store = ComfyCertificateTrustStore(localRoot)
        store.approve(trust)
        assertEquals(trust, store.find("$ORIGIN/proxy/comfy"))
        assertEquals(trust, store.find("https://LOCALHOST:35961/another/proxy"))
        assertNull(store.find("https://127.0.0.1:35961"))
        assertNull(store.find("https://localhost:35962"))
        assertNull(store.find("http://localhost:35961"))
        assertNull(store.find("localhost:35961"))
    }

    @Test
    fun httpApprovalNeverWritesTrustAndHttpForgetNeverRevokesHttps() {
        val store = ComfyCertificateTrustStore(localRoot)
        val trust = trustFor(ORIGIN)
        assertTrue(runCatching { store.approve(trust.copy(origin = "http://localhost:35961")) }.isFailure)
        assertFalse(savedFile.exists())
        store.approve(trust)
        val previousBytes = savedFile.readBytes()
        store.forget("http://localhost:35961")
        assertArrayEquals(previousBytes, savedFile.readBytes())
        assertEquals(trust, store.find(ORIGIN))
    }

    @Test
    fun noncanonicalOriginAndEmbeddedCredentialsCannotBeApproved() {
        val store = ComfyCertificateTrustStore(localRoot)
        val trust = trustFor(ORIGIN)
        for (invalidOrigin in listOf(
            "$ORIGIN/proxy/comfy",
            "$ORIGIN/",
            "https://LOCALHOST:35961",
            "https://user:secret@localhost:35961",
            "$ORIGIN?credential=secret",
        )) {
            assertTrue(invalidOrigin, runCatching { store.approve(trust.copy(origin = invalidOrigin)) }.isFailure)
        }
        assertFalse(savedFile.exists())
        assertFalse(localRoot.exists())
    }

    @Test
    fun wrongHashMalformedDerAndExtraDerCannotWriteAnApproval() {
        val store = ComfyCertificateTrustStore(localRoot)
        val trust = trustFor(ORIGIN)
        val der = certificate().encoded
        for (invalidTrust in listOf(
            trust.copy(certificateSha256 = "00".repeat(32)),
            trust.copy(certificateSha256 = "not-sha256"),
            trust.copy(certificateDerBase64 = "invalid base64"),
            trust.copy(certificateDerBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2))),
            trust.copy(certificateDerBase64 = Base64.getEncoder().encodeToString(der + byteArrayOf(0))),
            trust.copy(certificateDerBase64 = Base64.getEncoder().encodeToString(der + der)),
        )) {
            assertTrue(runCatching { store.approve(invalidTrust) }.isFailure)
        }
        assertFalse(savedFile.exists())
        assertFalse(localRoot.exists())
    }

    @Test
    fun invalidReplacementCannotOverwriteExistingApprovedBytes() {
        val store = ComfyCertificateTrustStore(localRoot)
        val trust = trustFor(ORIGIN)
        store.approve(trust)
        val previousBytes = savedFile.readBytes()
        assertTrue(runCatching { store.approve(trust.copy(certificateSha256 = "00".repeat(32))) }.isFailure)
        assertArrayEquals(previousBytes, savedFile.readBytes())
        assertEquals(trust, ComfyCertificateTrustStore(localRoot).find(ORIGIN))
    }

    @Test
    fun expiredCertificateCannotReceiveExplicitApproval() {
        val store = ComfyCertificateTrustStore(localRoot)
        val expiredTrust = trustFor(ORIGIN, "expired-tunnel-certificate.pem")
        assertTrue(runCatching { store.approve(expiredTrust) }.exceptionOrNull() is java.security.cert.CertificateExpiredException)
        assertFalse(savedFile.exists())
        assertFalse(localRoot.exists())
    }

    @Test
    fun tamperedFingerprintOrDerFailsClosedOnReadAndIsNotRewritten() {
        val trust = trustFor(ORIGIN)
        for (badTrust in listOf(
            trust.copy(certificateSha256 = "00".repeat(32)),
            trust.copy(certificateDerBase64 = "not a certificate"),
            trust.copy(origin = "http://localhost:35961"),
        )) {
            writeManifest(listOf(badTrust))
            val tamperedBytes = savedFile.readBytes()
            assertTrue(runCatching { ComfyCertificateTrustStore(localRoot).find(ORIGIN) }.isFailure)
            assertArrayEquals(tamperedBytes, savedFile.readBytes())
        }
    }

    @Test
    fun unknownVersionDuplicateOriginsAndUnknownFieldsFailClosed() {
        val trust = trustFor(ORIGIN)
        for (manifest in listOf(
            manifest(listOf(trust), version = 2),
            manifest(listOf(trust, trust)),
            manifest(listOf(trust)).dropLast(1) + ",\"unrecognizedAuthority\":true}",
            "{this is broken JSON",
        )) {
            requireNotNull(savedFile.parentFile).mkdirs()
            savedFile.writeText(manifest)
            assertTrue(runCatching { ComfyCertificateTrustStore(localRoot).find(ORIGIN) }.isFailure)
        }
    }

    @Test
    fun oversizedTrustFileIsRejectedBeforeParsingOrRewriting() {
        requireNotNull(savedFile.parentFile).mkdirs()
        savedFile.writeText(" ".repeat(1_048_577))
        val previousLength = savedFile.length()
        assertTrue(runCatching { ComfyCertificateTrustStore(localRoot).find(ORIGIN) }.isFailure)
        assertEquals(previousLength, savedFile.length())
    }

    @Test
    fun corruptExistingFileCannotBeSilentlyOverwrittenWithAnotherApproval() {
        requireNotNull(savedFile.parentFile).mkdirs()
        savedFile.writeText("corrupt existing local trust")
        val previousBytes = savedFile.readBytes()
        assertTrue(runCatching { ComfyCertificateTrustStore(localRoot).approve(trustFor(ORIGIN)) }.isFailure)
        assertArrayEquals(previousBytes, savedFile.readBytes())
    }

    @Test
    fun forgetIsDurableAndOnlyExplicitReapprovalRestoresTrust() {
        val trust = trustFor(ORIGIN)
        val store = ComfyCertificateTrustStore(localRoot)
        store.approve(trust)
        store.forget("$ORIGIN/proxy/comfy")
        assertNull(store.find(ORIGIN))
        val reopened = ComfyCertificateTrustStore(localRoot)
        assertNull(reopened.find(ORIGIN))
        assertNull(reopened.find("$ORIGIN/another/path"))
        // Reading a valid certificate fixture or holding an old in-memory record confers no trust.
        certificate()
        assertNull(ComfyCertificateTrustStore(localRoot).find(ORIGIN))
        reopened.approve(trust)
        assertEquals(trust, ComfyCertificateTrustStore(localRoot).find(ORIGIN))
    }

    @Test
    fun staleAtomicSidecarsCannotResurrectAForgottenCertificate() {
        val trust = trustFor(ORIGIN)
        val store = ComfyCertificateTrustStore(localRoot)
        store.approve(trust)
        val beforeRevocation = savedFile.readText()
        store.forget(ORIGIN)
        File(localRoot, "certificates.json.bak").writeText(beforeRevocation)
        File(localRoot, "certificates.json.new").writeText(beforeRevocation)
        assertNull(ComfyCertificateTrustStore(localRoot).find(ORIGIN))
        assertFalse(File(localRoot, "certificates.json.bak").exists())
        assertFalse(File(localRoot, "certificates.json.new").exists())
        assertNull(ComfyCertificateTrustStore(localRoot).find(ORIGIN))
    }

    @Test
    fun failedRevocationWriteImmediatelyDeniesOriginAndFailedReapprovalCannotClearDenial() {
        val trust = trustFor(ORIGIN)
        val store = ComfyCertificateTrustStore(localRoot)
        store.approve(trust)
        val previouslyCommittedBytes = savedFile.readBytes()
        // A nonempty directory occupying the atomic pending path deterministically blocks the
        // replacement on every OS without permission changes or any production files involved.
        val blockedPending = File(localRoot, "certificates.json.new")
        assertTrue(blockedPending.mkdir())
        File(blockedPending, "blocking-fixture").writeText("prevent deletion")
        assertTrue(runCatching { store.forget(ORIGIN) }.isFailure)
        assertArrayEquals(previouslyCommittedBytes, savedFile.readBytes())
        assertNull(store.find(ORIGIN))
        assertNull(store.find("$ORIGIN/proxy/comfy"))
        assertTrue(runCatching { store.approve(trust) }.isFailure)
        assertArrayEquals(previouslyCommittedBytes, savedFile.readBytes())
        assertNull(store.find(ORIGIN))
        // Persistence failed, so this intentionally claims only an in-process denial. A new
        // process cannot be promised durable revocation until the disk write actually succeeds.
    }

    @Test
    fun corruptFileRevocationFailsButInProcessDenialRemainsAndApprovalFailureCannotRestoreIt() {
        val trust = trustFor(ORIGIN)
        val store = ComfyCertificateTrustStore(localRoot)
        store.approve(trust)
        savedFile.writeText("corrupt local trust record")
        val corruptedBytes = savedFile.readBytes()
        assertTrue(runCatching { store.forget(ORIGIN) }.isFailure)
        assertNull(store.find(ORIGIN))
        assertTrue(runCatching { store.approve(trust) }.isFailure)
        assertNull(store.find(ORIGIN))
        assertArrayEquals(corruptedBytes, savedFile.readBytes())
        // Fresh stores still reject corrupt bytes; no repair, implicit approval or fake success.
        assertTrue(runCatching { ComfyCertificateTrustStore(localRoot).find(ORIGIN) }.isFailure)
    }

    @Test
    fun replacingOneOriginPreservesOtherExplicitApprovals() {
        val first = trustFor(ORIGIN)
        val second = trustFor("https://localhost:35962")
        val updated = trustFor(ORIGIN, "wrong-host-tunnel-certificate.pem")
        val store = ComfyCertificateTrustStore(localRoot)
        store.approve(first)
        store.approve(second)
        store.approve(updated)
        val reopened = ComfyCertificateTrustStore(localRoot)
        assertEquals(updated, reopened.find(ORIGIN))
        assertEquals(second, reopened.find(second.origin))
        reopened.forget(ORIGIN)
        assertNull(reopened.find(ORIGIN))
        assertEquals(second, reopened.find(second.origin))
        // The persistence store records explicit consent; the separate TLS client enforces SAN.
    }

    @Test
    fun certificateCountLimitRejectsNewApprovalWithoutChangingExistingFile() {
        val store = ComfyCertificateTrustStore(localRoot)
        val approvals = (1..32).map { trustFor("https://localhost:${35000 + it}") }
        approvals.forEach(store::approve)
        val previousBytes = savedFile.readBytes()
        assertTrue(runCatching { store.approve(trustFor("https://localhost:35999")) }.isFailure)
        assertArrayEquals(previousBytes, savedFile.readBytes())
        approvals.forEach { assertEquals(it, ComfyCertificateTrustStore(localRoot).find(it.origin)) }
    }

    @Test
    fun deviceLocalApprovalsNeverTouchThePortableAppStateDirectory() {
        val portableRoot = temporaryFolder.newFolder("files", "novelseek")
        val appState = File(portableRoot, "app_state.json").apply { writeText("{\"projects\":[],\"apiKeys\":\"untouched\"}") }
        val before = appState.readBytes()
        val store = ComfyCertificateTrustStore(localRoot)
        store.approve(trustFor(ORIGIN))
        assertArrayEquals(before, appState.readBytes())
        assertEquals(listOf("app_state.json"), portableRoot.listFiles()!!.map { it.name })
        assertFalse(savedFile.absolutePath.startsWith(portableRoot.absolutePath + File.separator))
        store.forget(ORIGIN)
        assertArrayEquals(before, appState.readBytes())
    }

    private fun writeManifest(trusts: List<ComfyCertificateTrust>) {
        requireNotNull(savedFile.parentFile).mkdirs()
        savedFile.writeText(manifest(trusts))
    }

    private fun manifest(trusts: List<ComfyCertificateTrust>, version: Int = 1): String = JsonObject(mapOf(
        "version" to JsonPrimitive(version),
        "certificates" to JsonArray(trusts.map { Json.encodeToJsonElement(ComfyCertificateTrust.serializer(), it) }),
    )).toString()

    private fun trustFor(address: String, resource: String = "valid-tunnel-certificate.pem"): ComfyCertificateTrust {
        val certificate = certificate(resource)
        return ComfyCertificateTrust(
            ComfyTlsTrust.originFor(address),
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) },
            Base64.getEncoder().encodeToString(certificate.encoded),
        )
    }

    private fun certificate(resource: String = "valid-tunnel-certificate.pem"): X509Certificate =
        javaClass.getResourceAsStream("/comfy-tls/$resource")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    companion object { private const val ORIGIN = "https://localhost:35961" }
}
