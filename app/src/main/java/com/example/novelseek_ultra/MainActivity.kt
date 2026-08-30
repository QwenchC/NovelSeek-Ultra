package com.example.novelseek_ultra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.novelseek_ultra.agent.AgentController
import com.example.novelseek_ultra.agent.AgentForegroundService
import com.example.novelseek_ultra.agent.AgentRunDiagnosisTrigger
import com.example.novelseek_ultra.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val requestNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }
    private var resumeDiagnosisGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Needed for the agent's background (screen-off) run notification on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppRoot() }
    }

    override fun onResume() {
        super.onResume()
        val generation = ++resumeDiagnosisGeneration
        scheduleAgentResumeDiagnosis(generation = generation, attempt = 0)
    }

    override fun onPause() {
        // Invalidate delayed callbacks so a backgrounded activity neither starts a foreground
        // diagnosis nor dismisses a recovery notice that the user has not yet returned to inspect.
        resumeDiagnosisGeneration++
        super.onPause()
    }

    private fun scheduleAgentResumeDiagnosis(generation: Long, attempt: Int) {
        val delayMillis = if (attempt == 0) 0L else AGENT_RESUME_DIAGNOSIS_RETRY_DELAY_MS
        window.decorView.postDelayed({
            if (generation != resumeDiagnosisGeneration || isFinishing || isDestroyed) {
                return@postDelayed
            }
            val controller = AgentController.active
            if (controller == null) {
                if (attempt + 1 < AGENT_RESUME_DIAGNOSIS_MAX_ATTEMPTS) {
                    scheduleAgentResumeDiagnosis(generation = generation, attempt = attempt + 1)
                }
                return@postDelayed
            }

            // Dismiss the durable notice only after a real controller has completed the foreground
            // diagnosis call. If initialization never succeeds, the notice deliberately remains.
            controller.diagnoseAndRecoverIfStalled(AgentRunDiagnosisTrigger.APP_FOREGROUND)
            if (generation == resumeDiagnosisGeneration && !isFinishing && !isDestroyed) {
                AgentForegroundService.dismissRecoveryNotice(this)
            }
        }, delayMillis)
    }

    private companion object {
        const val AGENT_RESUME_DIAGNOSIS_MAX_ATTEMPTS = 8
        const val AGENT_RESUME_DIAGNOSIS_RETRY_DELAY_MS = 150L
    }
}
