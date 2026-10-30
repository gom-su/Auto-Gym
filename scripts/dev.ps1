param([ValidateSet('check','test','build','emulator','install','run','logs')][string]$Action = 'check')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
$localJdk = Get-ChildItem (Join-Path $projectRoot '.tools/jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (Test-Path (Join-Path $projectRoot '.sdk/platforms/android-36')) { $env:ANDROID_HOME = Join-Path $projectRoot '.sdk' }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools/avd'
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
switch ($Action) {
    'check' {
        & "$env:JAVA_HOME/bin/java.exe" -version
        & ./gradlew.bat --version
        Write-Output "SDK: $env:ANDROID_HOME"
        Get-ChildItem "$env:ANDROID_HOME/platforms","$env:ANDROID_HOME/build-tools" -Name
        & $adb devices -l
        & "$env:ANDROID_HOME/emulator/emulator.exe" -list-avds
        & "$env:ANDROID_HOME/emulator/emulator.exe" -accel-check
    }
    'test' { & ./gradlew.bat testDebugUnitTest --console=plain; if ($LASTEXITCODE) { throw 'Tests failed' } }
    'build' { & ./gradlew.bat assembleDebug --console=plain; if ($LASTEXITCODE) { throw 'Build failed' } }
    'emulator' {
        Start-Process -FilePath "$env:ANDROID_HOME/emulator/emulator.exe" -ArgumentList '-avd Workout_API_36 -no-snapshot -gpu swiftshader -memory 2048 -cores 2' -WindowStyle Hidden
    }
    'install' { & $adb install -r app/build/outputs/apk/debug/app-debug.apk; if ($LASTEXITCODE) { throw 'Install failed' } }
    'run' { & $adb shell am start -n com.example.workoutpoc/.MainActivity; if ($LASTEXITCODE) { throw 'Launch failed' } }
    'logs' { & $adb logcat 'WorkoutPoC:E' 'WorkoutService:W' 'WorkoutOverlay:W' 'AndroidRuntime:E' '*:S' }
}
