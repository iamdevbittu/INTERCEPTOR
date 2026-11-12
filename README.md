# INTERCEPTOR

**Rootless Android browser with full network inspection and a human-in-the-loop request firewall.**

INTERCEPTOR is an Android WebView browser that intercepts every HTTP/HTTPS request before it
leaves the device, lets you approve, block or edit it, and then shows you exactly what came back.
No root, no VPN service, no system proxy and no custom CA installed — it works entirely inside the app.

Every request passes through a strict pipeline:

```
WEBSITE -> NETWORK INTERCEPTOR -> REQUEST CLASSIFICATION -> USER APPROVAL
        -> ALLOW / BLOCK / EDIT -> NETWORK TRANSMISSION -> RESPONSE INSPECTION -> WEBSITE
```

Requests that are not explicitly approved **fail closed**. Unknown domains default to
`ASK_EVERY_TIME`, and `PENDING`, `BLOCKED` and `TIMED_OUT` requests are never transmitted.

## Features

- **Live traffic inspection** — 10-state request lifecycle (`CREATED`, `PENDING`, `APPROVED`,
  `BLOCKED`, `MODIFIED`, `SENT`, `COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT`).
- **Per-request approval** with a configurable timeout; unapproved requests never reach the network.
- **Full pre-transmission request editor** — URL, method, headers, query parameters, cookies and
  JSON body, with real-time validation and a diff summary.
- **Response inspector** — syntax-highlighted JSON, redirect-chain tracking, TLS certificate
  details, credential-redacted cURL export and one-tap replay.
- **Domain firewall** — priority-ordered rules (`Always Allow`, `Always Block`, `Ask Every Time`,
  `Allow Once`, `Temporary Allow`) with wildcard support (`*.example.com`), hit counters and history.
- **Local history** — persistent SQLite storage via Room for traffic records, rules and bookmarks.
- **Security validation** — scheme whitelisting, CRLF-injection prevention, JSON validation and
  credential redaction.

See [`ARCHITECTURE_AND_SECURITY.md`](ARCHITECTURE_AND_SECURITY.md) for the module layout, the
security model and the known architectural limitations of rootless WebView interception.

## Requirements

- JDK 17 or newer (CI uses JDK 21)
- Android SDK with **platform 36.1** and **build-tools 36.1.0**
- Android Studio (optional) — the Gradle wrapper is enough

`minSdk` is 24 (Android 7.0); `targetSdk`/`compileSdk` is 36.

## Build

The Gradle wrapper is committed, so no local Gradle install is required:

```bash
git clone https://github.com/iamdevbittu/INTERCEPTOR.git
cd INTERCEPTOR

./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
```

### Signing

**Signing keys are deliberately not committed to this repository.** `app/build.gradle.kts` expects:

| Build type | Keystore | Alias | Passwords |
| --- | --- | --- | --- |
| `debug` | `debug.keystore` in the project root | `androiddebugkey` | `android` / `android` |
| `release` | `$KEYSTORE_PATH` (default `my-upload-key.jks`) | `upload` | `$STORE_PASSWORD` / `$KEY_PASSWORD` |

Create a local debug keystore so `assembleDebug` can run:

```bash
keytool -genkeypair -v -keystore debug.keystore -storetype JKS \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

For release builds, generate an upload key and export `KEYSTORE_PATH`, `STORE_PASSWORD` and
`KEY_PASSWORD`. **Keep that key safe and backed up** — losing it means an installed release build
can never be updated again.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

- `app/src/test/java/com/example/InterceptorEngineTest.kt` — behaviour suite that runs against a
  real in-process localhost TCP server (`LocalSandboxServer`) to verify actual transmission,
  firewall decisions, request editing and timeout paths.
- `app/src/test/java/com/example/InterceptorRobolectricTest.kt` — Robolectric + Compose UI and
  Room database integration tests.

## Continuous integration

[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) builds both variants on every
push to `main` and attaches them to the rolling `apk-latest` release, which gives permanent direct
download links:

- `https://github.com/iamdevbittu/INTERCEPTOR/releases/download/apk-latest/INTERCEPTOR-1.0.0-debug.apk`
- `https://github.com/iamdevbittu/INTERCEPTOR/releases/download/apk-latest/INTERCEPTOR-1.0.0-release.apk`
- `.../SHA256SUMS.txt` — verify the download hash before installing

The workflow generates throwaway keystores when no signing secrets are configured. To make release
builds upgrade-in-place instead, add these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 my-upload-key.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_PASSWORD` | key password |

## Install

1. Download an APK from the release above (or build it yourself).
2. Enable **Install unknown apps** for your file manager or browser.
3. Verify the SHA-256 checksum, then install.

```bash
sha256sum -c SHA256SUMS.txt
```

## Repository layout

```
app/src/main/java/com/example/
├── browser/            WebView browser UI
├── database/           Room entities, DAOs, database
├── firewall/           Domain rule engine + rules screen
├── interceptor/        Approval pipeline + live traffic screen
├── network/            Models, OkHttp executor, local sandbox server
├── proxy/              WebView interception bridge (native + JS hooks)
├── requesteditor/      Pre-transmission request editor
├── responseinspector/  Response viewer, replay, cURL export
├── security/           Validation and redaction
├── settings/           History and settings screens
└── ui/theme/           Compose theme, colors, typography
```

## License

No license file is currently included. Add one before distributing or accepting contributions.
