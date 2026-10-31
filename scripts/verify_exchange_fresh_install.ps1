param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+\.json$')][string]$SourceFileName,
    [ValidatePattern('^com\.example\.workoutpoc\.[a-z0-9]+\.exchangeqa$')]
    [string]$TestApplicationId = ('com.example.workoutpoc.qa' + (Get-Date -Format 'yyyyMMddHHmmss') + '.exchangeqa')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
$localJdk = Get-ChildItem (Join-Path $projectRoot '.tools/jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (Test-Path (Join-Path $projectRoot '.sdk/platforms/android-36')) { $env:ANDROID_HOME = Join-Path $projectRoot '.sdk' }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
function Invoke-TestAdb {
    & $adb -s $Serial @args
    if ($LASTEXITCODE) { throw 'ADB command failed' }
}
$existing = Invoke-TestAdb shell pm list packages $TestApplicationId
if ($existing -contains "package:$TestApplicationId") { throw 'Use a new TestApplicationId: this check never clears existing data.' }
Invoke-TestAdb shell ls "/sdcard/Download/$SourceFileName"
$evidenceDirectory = Join-Path $projectRoot ".local/ent-02-fresh-install/$TestApplicationId"
New-Item -ItemType Directory -Force $evidenceDirectory | Out-Null
$initFile = Join-Path $evidenceDirectory 'test-install.init.gradle'
@"
gradle.beforeProject { p ->
    if (p.path == ':app') {
        p.afterEvaluate { p.android.defaultConfig.applicationId = '$TestApplicationId' }
    }
}
"@ | Set-Content -Encoding utf8 $initFile
try {
    & ./gradlew.bat -I $initFile assembleDebug assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE) { throw 'Test installation build failed' }
    Invoke-TestAdb install -r app/build/outputs/apk/debug/app-debug.apk
    Invoke-TestAdb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    $testOutput = Invoke-TestAdb shell am instrument -w -e class com.example.workoutpoc.ExchangeFreshInstallTest -e exchangeExportFile $SourceFileName "$TestApplicationId.test/androidx.test.runner.AndroidJUnitRunner"
    $testOutput | Set-Content -Encoding utf8 (Join-Path $evidenceDirectory 'instrumentation.txt')
    $testOutput | Write-Output
    if (($testOutput -join "`n") -notmatch 'OK \(1 test\)' -or ($testOutput -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED') {
        throw 'Fresh installation acceptance failed; inspect local evidence.'
    }
    & python scripts/verify_exchange_restore.py --serial $Serial --package $TestApplicationId --evidence (Join-Path $evidenceDirectory 'restoration')
    if ($LASTEXITCODE) { throw 'Fresh installation restoration failed' }
    Write-Output "Verified fresh installation: $TestApplicationId. It remains installed for manual review."
} finally {
    # Keep normal build outputs ready for development; no source or app data was changed.
    & ./gradlew.bat assembleDebug assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE) { throw 'Could not restore standard build outputs' }
}
