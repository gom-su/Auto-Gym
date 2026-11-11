# Debug updates through Firebase App Distribution

Only the signed APK is uploaded. No workout history, measurements, saved Claude key or device files are part of the build. The app does not need a Firebase SDK, Analytics, a Google Services configuration file or a new runtime network integration for APK delivery.

## Normal delivery

Every push/merge to `main` runs `.github/workflows/firebase-distribution.yml` on a standard Ubuntu GitHub runner. It runs JVM tests, Android lint, a debug build and the Python MCP suite. Once they pass, a separate job builds the APK with the original debug signing key, checks its certificate and uploads it to Firebase, notifying the configured tester. New pushes supersede an older running delivery.

Pull requests only run verification: they cannot use the signing key or the Firebase identity. Manual workflow runs are accepted for delivery only on `main`.

Accept the Firebase invitation once using the Google account configured as tester. After that, Firebase sends new-build notifications with the installation link. Android still asks you to confirm installing the update. The app's existing package name and signing certificate are retained so an update can replace the installed app without uninstalling it.

## Configuration

Repository variables:

| Variable | Purpose |
| --- | --- |
| `FIREBASE_PROJECT_ID` | Dedicated Firebase project |
| `FIREBASE_APP_ID` | Registered Android app for `com.example.workoutpoc` |
| `GOOGLE_WORKLOAD_IDENTITY_PROVIDER` | GitHub OIDC federation provider |
| `GOOGLE_SERVICE_ACCOUNT` | Account with only `roles/firebaseappdistro.admin` on the dedicated project |
| `ANDROID_DEBUG_CERT_SHA256` | SHA-256 of the original APK's signing certificate |

Repository secrets:

| Secret | Purpose |
| --- | --- |
| `ANDROID_DEBUG_KEYSTORE_BASE64` | Original local debug keystore, base64 encoded |
| `FIREBASE_TESTERS` | Comma-separated tester emails; kept out of public source |

The federation provider restricts the numeric repository and owner IDs, `refs/heads/main` and this exact workflow path. Its dedicated service account has no exported private JSON key. The original Android keystore is restored only under the ephemeral runner's temporary directory, removed at job end and never uploaded as an artifact. Firebase CLI output, which includes a signed binary-download URL, is also kept out of public workflow logs.

The Firebase project uses Spark with billing disabled. The workflow does not invoke Test Lab, the App Testing agent, Cloud Build, Cloud Storage buckets or other billable products. GitHub Actions uses standard public-repository runners; there are no uploaded build artifacts or configured dependency caches.

## Versions and local builds

Distributed builds use a Unix-seconds `versionCode` (within Android's supported integer range) and a version name containing that number and the source commit. A subsequent workflow run uses a larger number; old Firebase releases retain their original number. The workflow never uninstalls the app and never uses a downgrade flag.

Ordinary local Gradle builds keep version code 2 and version name 1.0.1. After installing an automatically numbered build, use a larger code when building a local APK you intend to install over it:

```powershell
$buildCode = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
./gradlew.bat :app:assembleDebug "-PautoGymVersionCode=$buildCode" "-PautoGymVersionName=1.0.1-debug.$buildCode"
```

`AUTO_GYM_DEBUG_KEYSTORE`, when set, must point to the existing keystore with the standard Android debug alias/password. A missing supplied file fails the build rather than generating a different key. Before compiling, the workflow checks the restored key's DER certificate hash. After compiling, it explicitly signs the final APK with that key using `apksigner` and checks its certificate against `ANDROID_DEBUG_CERT_SHA256` before uploading. Only public certificate fingerprints are printed; private key material is never printed.

## Verification

Use the workflow run and Firebase release details as delivery evidence. A successful upload/notification does not establish that the user accepted the invitation, downloaded or installed the APK; Firebase tracks those separately. Device acceptance remains a separate check.

Sources: [Firebase CLI distribution](https://firebase.google.com/docs/app-distribution/android/distribute-cli), [Google GitHub authentication](https://github.com/google-github-actions/auth), [Android app signing](https://developer.android.com/studio/publish/app-signing).
