package com.example.novelseek_ultra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.novelseek_ultra.data.ai.ComfyCertificateInfo
import com.example.novelseek_ultra.data.ai.ComfyCertificateTrust
import com.example.novelseek_ultra.data.ai.ComfyTlsTrust
import com.example.novelseek_ultra.data.ai.ComfyUIEndpoint
import com.example.novelseek_ultra.ui.AppViewModel
import com.example.novelseek_ultra.util.tx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One explicit, device-local approval. Observing a network certificate never silently trusts it. */
@Composable
fun ComfyCertificateSettings(vm: AppViewModel, language: String, url: String, enabled: Boolean,
    onUseHttps: (String) -> Unit, onTrustChanged: () -> Unit) {
    val revision by vm.comfyTrustRevision.collectAsState()
    val normalized = remember(url) { runCatching { ComfyUIEndpoint.normalize(url) }.getOrNull() }
    val origin = remember(normalized) { normalized?.takeIf { it.startsWith("https://") }?.let { ComfyTlsTrust.originFor(it) } }
    val currentOrigin by rememberUpdatedState(origin)
    val scope = rememberCoroutineScope()
    var trusted by remember { mutableStateOf<ComfyCertificateTrust?>(null) }
    var certificate by remember { mutableStateOf<ComfyCertificateInfo?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var actionRevision by remember { mutableStateOf(0) }

    LaunchedEffect(origin) { message = null }

    LaunchedEffect(origin, revision) {
        actionRevision++
        certificate = null
        trusted = null
        // Avoid a disk read on every keystroke; proxy path changes keep the same origin.
        delay(250)
        origin?.let {
            try { trusted = vm.trustedComfyCertificate(it) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = tx(language, "无法读取本机证书授权，未降低连接安全校验。", "Could not load local certificate approval; normal verification remains enabled.") }
        }
    }

    if (origin == null) {
        if (normalized?.startsWith("http://") == true) {
            TextButton(enabled = enabled && !working, onClick = {
                normalized.toHttpUrlOrNull()?.newBuilder()?.scheme("https")?.build()?.let { onUseHttps(it.toString().removeSuffix("/")) }
            }) { Text(tx(language, "切换 HTTPS 并核对隧道证书", "Use HTTPS and verify tunnel certificate")) }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tx(language, "此服务器的证书信任", "Certificate approval for this server"), style = MaterialTheme.typography.titleSmall)
        Text(origin, style = MaterialTheme.typography.labelSmall)
        trusted?.let {
            Text(tx(language, "已在本机绑定 SHA-256", "SHA-256 approved on this device"), style = MaterialTheme.typography.labelSmall)
            Text(formatComfyFingerprint(it.certificateSha256), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        Text(tx(language,
            "仅信任这个 HTTPS 主机、端口和已核对的证书，仍验证域名与有效期。不需要自有域名；证书变化时必须重新核对。授权不随备份传到其他设备。",
            "Approve only this HTTPS host, port and verified certificate. Hostname and validity checks remain. No owned domain is required; changed certificates need a new approval. Backups do not transfer this approval."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled = enabled && !working, onClick = {
            val expectedOrigin = origin
            val expectedRevision = ++actionRevision
            working = true; message = null
            scope.launch {
                try {
                    val info = vm.inspectComfyCertificate(expectedOrigin)
                    if (currentOrigin == expectedOrigin && actionRevision == expectedRevision) certificate = info
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (currentOrigin == expectedOrigin && actionRevision == expectedRevision) message = tx(language, "证书读取失败，请检查 HTTPS 地址、网络和隧道在线状态。", "Could not inspect the certificate; check HTTPS, network and tunnel status.")
                } finally { working = false }
            }
        }) {
            if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(tx(language, "查看并核对服务器证书", "Inspect and verify certificate"))
        }
        TextButton(enabled = enabled && !working, onClick = {
            val expectedOrigin = origin
            working = true; message = null
            scope.launch {
                try {
                    vm.forgetComfyCertificate(expectedOrigin)
                    if (currentOrigin == expectedOrigin) { trusted = null; onTrustChanged() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (currentOrigin == expectedOrigin) message = tx(language, "本进程已停止使用证书授权，但未能保存撤销，请重试；重启后可能恢复旧记录。", "Approval is blocked in this process, but revocation could not be saved. Retry; a restart may restore the old record.") }
                finally { working = false }
            }
        }) { Text(tx(language, "撤销此服务器的信任", "Revoke this server's approval")) }
        message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }

    val info = certificate?.takeIf { it.origin == origin } ?: return
    var fingerprintVerified by remember(info) { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    val validNow = System.currentTimeMillis() in info.notBeforeEpochMs..info.notAfterEpochMs
    AlertDialog(
        onDismissRequest = { if (!working) certificate = null },
        title = { Text(tx(language, "核对并信任此服务器证书", "Verify and approve this certificate")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info.origin)
                Text("SHA-256", style = MaterialTheme.typography.labelMedium)
                Text(formatComfyFingerprint(info.certificateSha256), fontFamily = FontFamily.Monospace)
                Text(tx(language, "证书主体：", "Subject: ") + info.subject.take(512), style = MaterialTheme.typography.bodySmall)
                Text(tx(language, "签发者：", "Issuer: ") + info.issuer.take(512), style = MaterialTheme.typography.bodySmall)
                Text(tx(language, "有效期：", "Validity: ") + dateFormat.format(Date(info.notBeforeEpochMs)) + " — " + dateFormat.format(Date(info.notAfterEpochMs)), style = MaterialTheme.typography.bodySmall)
                Text(tx(language,
                    "这只是网络上观察到的证书，尚未证明服务器身份。请与电脑上的樱花 FRP 证书或可靠来源核对完整 SHA-256；不要仅因网络弹窗显示了它就批准。不会安装系统证书或信任其他服务器。",
                    "This is an observed certificate, not authenticated identity. Verify its full SHA-256 against your PC's FRP certificate or another reliable source; do not approve solely because this dialog displays it. No system certificate is installed and no other server is trusted."), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = fingerprintVerified, onCheckedChange = { fingerprintVerified = it }, enabled = !working && validNow)
                    Text(tx(language, "我已核对完整指纹，确认这是我的服务器", "I verified the full fingerprint and this is my server"), style = MaterialTheme.typography.bodySmall)
                }
                if (!validNow) Text(tx(language, "证书未生效或已过期，不能批准。", "Certificate is not yet valid or has expired; approval is blocked."), color = MaterialTheme.colorScheme.error)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = fingerprintVerified && validNow && !working && enabled, onClick = {
                val expectedOrigin = info.origin
                working = true; message = null
                scope.launch {
                    try {
                        vm.approveComfyCertificate(expectedOrigin, info)
                        if (currentOrigin == expectedOrigin) { certificate = null; onTrustChanged() }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (currentOrigin == expectedOrigin) message = tx(language, "证书授权未能保存，请重新核对后重试。", "Could not save approval; verify again and retry.") }
                    finally { working = false }
                }
            }) { Text(tx(language, "仅信任此主机和端口", "Approve only this host and port")) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = { certificate = null }) { Text(tx(language, "取消", "Cancel")) } },
    )
}

internal fun formatComfyFingerprint(hash: String): String = hash.uppercase(Locale.US).chunked(2).joinToString(":")
