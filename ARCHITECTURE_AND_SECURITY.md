# INTERCEPTOR — Architecture, Security & Build Documentation

**Rootless Android Browser | Full Network Inspection | Human-in-the-Loop API Firewall**

---

## 1. Architecture Overview

INTERCEPTOR is structured into clean, decoupled modules inside `app/src/main/java/com/example/`:

- **`network/`** (`NetworkModels.kt`, `OkHttpNetworkExecutor.kt`, `LocalSandboxServer.kt`):
  - Defines the 10-state request lifecycle (`CREATED`, `PENDING`, `APPROVED`, `BLOCKED`, `MODIFIED`, `SENT`, `COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT`).
  - Executes approved/modified upstream HTTP/1.1 and HTTP/2 requests via OkHttp with redirect hop tracking, TLS handshake inspection, and binary payload protection.
  - Embeds a localhost-only (`127.0.0.1`) verification target server for deterministic automated testing and live in-app firewall testing.
- **`interceptor/`** (`RequestInterceptorEngine.kt`, `LiveTrafficScreen.kt`):
  - Enforces the strict human-in-the-loop pipeline:
    `WEBSITE -> NETWORK INTERCEPTOR -> REQUEST CLASSIFICATION -> USER APPROVAL -> ALLOW / BLOCK / EDIT -> NETWORK TRANSMISSION -> RESPONSE INSPECTION -> WEBSITE`.
  - Uses `CompletableDeferred<UserApprovalDecision>` per request ID and `withTimeoutOrNull(approvalTimeoutMs)` so unapproved requests fail closed (`TIMED_OUT`) and are **never** transmitted silently.
- **`proxy/`** (`WebViewInterceptorBridge.kt`):
  - Combines native Chromium `WebViewClient.shouldInterceptRequest` with an injected JavaScript `fetch()` and `XMLHttpRequest` hook (`InterceptorNativeBridge`) to capture POST/PUT/PATCH/DELETE payloads, JSON bodies, form fields, headers, and cookies prior to transmission.
- **`requesteditor/`** (`RequestEditorSheet.kt`):
  - Full pre-transmission packet editor supporting URL, HTTP method, headers, query parameters, cookies, and JSON payload editing with real-time validation and diff summaries.
- **`responseinspector/`** (`ResponseInspectorComponents.kt`):
  - Syntax-highlighted JSON viewer, redirect chain inspector, TLS certificate viewer, credential-redacted cURL exporter, and one-tap request replay.
- **`firewall/`** (`DomainFirewallEngine.kt`, `FirewallRulesScreen.kt`):
  - Evaluates domain rules by priority (`Always Allow`, `Always Block`, `Ask Every Time`, `Allow Once`, `Temporary Allow`) with wildcard domain support (`*.example.com`), hit counters, and rule history.
- **`database/`** (`InterceptorDatabase.kt`):
  - Local SQLite persistence via AndroidX Room (`traffic_records`, `domain_rules`, `rule_history`, `bookmarks`).
- **`security/`** (`SecurityValidator.kt`):
  - Enforces scheme whitelisting (`http`/`https`), header CRLF injection prevention, JSON syntax validation, credential redaction, and transparent capability disclosures.

---

## 2. Security & Capability Disclosures

1. **Zero Silent Transmission in Strict Mode**: Unknown domains default to `ASK_EVERY_TIME`. `PENDING`, `BLOCKED`, and `TIMED_OUT` requests never reach `NetworkExecutor`.
2. **TLS & Certificate Verification**: Upstream HTTPS connections validate server certificates against the Android OS system `X509TrustManager`. Certificate verification is never globally disabled, and certificates are never silently installed.
3. **Localhost Binding**: The built-in verification server (`LocalSandboxServer`) binds exclusively to `127.0.0.1`.
4. **Known Architectural Limitations (Rootless Android WebView)**:
   - **Certificate Pinning / mTLS**: Endpoints requiring hardware client certificates or public-key pinning cannot be decrypted by a custom CA.
   - **QUIC / HTTP/3 & Raw WebRTC UDP**: Raw UDP sockets bypass standard HTTP hooks; upstream requests routed via INTERCEPTOR's OkHttp engine negotiate HTTP/2 or HTTP/1.1 over verified TLS.
   - **Cross-Origin Subframe Native POST Streams**: While main-frame and same-origin `fetch()` / `XMLHttpRequest` bodies are fully captured and editable via the JS bridge, Android's native `WebViewClient.shouldInterceptRequest` API does not expose raw POST body streams for cross-origin iframes (they are still subject to URL/domain/header firewall allow/block rules).

---

## 3. Build, Test & Installation Instructions

### Compile Debug & Release APKs
```bash
./gradlew assembleDebug assembleRelease
```

### Run Automated Unit & Robolectric QA Suite
```bash
./gradlew :app:testDebugUnitTest
```

### Install APK on Device
Enable **Install unknown apps** for your file manager or browser on Android, transfer the signed APK (`app/build/outputs/apk/debug/app-debug.apk` or `app/build/outputs/apk/release/app-release.apk`), and verify the SHA-256 checksum before installing.
