# Migration: production capability layer

The additions are backward-compatible. Existing string-based permission calls
remain available; new code should use `PermissionKind`.

## Host changes

- Android FCM projects place their client configuration at
  `.pam/google-services.json` or `google-services.json`; PAM supplies the
  messaging service automatically. Existing custom services may continue to
  call `PamPushNotifications.reportReceived()`.
- iOS adds relevant usage descriptions and forwards notification delegate
  callbacks described in `production-capabilities.md`.
- iOS runtime hosts may pass the optional `onDiagnostic` callback.

## Behavior changes

- WebView roots accept `https`, `http` and `file`; inline HTML is unchanged.
- SQLite results over 1,000 rows or 256 columns require pagination.
- File imports abort immediately after crossing 64 MiB.
- Media pauses with the host lifecycle and only resumes if previously playing.

No protocol number was reassigned. `WebViewAllowedHosts` is property `366`.
