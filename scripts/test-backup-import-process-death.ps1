[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$AllowPhysicalDevice
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$javaHome = "D:\Program Files\Android\Android Studio\jbr"
$appId = "com.example.novelseek_ultra"
$runner = "$appId.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "$appId.data.BackupImportRecoveryInstrumentedTest"
$phaseArg = "backupImportProcessDeathPhase"
$checkpointArg = "backupImportCheckpoint"
$checkpoints = @(
    "MANIFEST_ONLY",
    "PARTIAL_FILES",
    "SECRETS_WRITTEN",
    "STATE_WRITTEN",
    "COMMITTED"
)

if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "ADB not found at $adb"
}
if (-not (Test-Path -LiteralPath $javaHome -PathType Container)) {
    throw "Android Studio JDK not found at $javaHome"
}

$deviceLines = @(& $adb devices | Where-Object { $_ -match '^(\S+)\s+device(?:\s|$)' })
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($deviceLines.Count -ne 1) {
        throw "Connect exactly one Android emulator, or pass -Serial. Connected devices: $($deviceLines.Count)"
    }
    $Serial = ([regex]::Match($deviceLines[0], '^(\S+)')).Groups[1].Value
} elseif (-not ($deviceLines | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device(?:\s|$)" })) {
    throw "Device $Serial is not connected and authorized"
}

$adbPrefix = @("-s", $Serial)
$isEmulator = ((& $adb @adbPrefix shell getprop ro.kernel.qemu) -join "").Trim() -eq "1"
if (-not $isEmulator -and -not $AllowPhysicalDevice) {
    throw "Refusing to run a process-kill test on physical device $Serial. Use an emulator or explicitly pass -AllowPhysicalDevice."
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$CommandArgs,
        [switch]$AllowFailure
    )

    $output = @(& $script:adb @script:adbPrefix @CommandArgs 2>&1)
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "ADB command failed with exit code $exitCode`: $($CommandArgs -join ' ')"
    }
    return $output
}

function Invoke-InstrumentationChecked {
    param([Parameter(Mandatory = $true)][string[]]$CommandArgs)

    $output = Invoke-Adb -CommandArgs $CommandArgs
    $text = $output -join "`n"
    if ($text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=' -or $text -notmatch 'OK \(') {
        throw "Instrumentation assertion failed"
    }
}

Push-Location $projectRoot
try {
    $env:JAVA_HOME = $javaHome
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle Android test build failed" }

    $appApk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
    $testApk = Join-Path $projectRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    $null = Invoke-Adb -CommandArgs @("install", "-r", $appApk)
    $null = Invoke-Adb -CommandArgs @("install", "-r", $testApk)

    Write-Host "Running Android-runtime journal and Keystore tests on $Serial"
    Invoke-InstrumentationChecked -CommandArgs @(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", $testClass,
        $runner
    )
    $null = Invoke-Adb -CommandArgs @("shell", "am", "force-stop", $appId)

    foreach ($checkpoint in $checkpoints) {
        Write-Host "Process-death checkpoint: $checkpoint"
        # Phase 1 is expected to abort because the test deliberately kills its own process.
        $null = Invoke-Adb -AllowFailure -CommandArgs @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", $phaseArg, "prepare",
            "-e", $checkpointArg, $checkpoint,
            "-e", "class", "$testClass#prepareAndKillProcessWithActiveJournal",
            $runner
        )
        $null = Invoke-Adb -CommandArgs @("shell", "am", "force-stop", $appId)

        Invoke-InstrumentationChecked -CommandArgs @(
            "shell", "am", "instrument", "-w", "-r",
            "-e", $phaseArg, "verify",
            "-e", $checkpointArg, $checkpoint,
            "-e", "class", "$testClass#verifyRecoveryAfterProcessDeath",
            $runner
        )
        $null = Invoke-Adb -CommandArgs @("shell", "am", "force-stop", $appId)
    }

    Write-Host "All backup-import Android process-death checkpoints passed."
} finally {
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        try { $null = Invoke-Adb -AllowFailure -CommandArgs @("shell", "am", "force-stop", $appId) } catch {}
    }
    Pop-Location
}
