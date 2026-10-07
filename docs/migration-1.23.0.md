# Migrating to Pam Native 1.23.0

## Per-account notification action credentials

Rebuild the Android and iOS hosts after updating. Existing endpoints that use
`bearerFromStorage()` keep working unchanged.

Multi-account apps should replace a single stored token with
`ActionEndpoint::bearerFromCredential('user_id', 'recipient_user_id')` and keep
`NotificationCredentials` in sync with the signed-in accounts (`sync()` at boot
and after every account change, `remove()` / `clear()` on logout). The reply or
mark-as-read is then sent natively, even with the app killed, as the account
the push was addressed to. A push whose account has no credential on the device
sends nothing and is dismissed; `NotificationAction::$credentialMissing` tells
PHP not to retry it with another session.

`NotificationAction` gained the optional trailing constructor argument
`credentialMissing` (default `false`). No numeric protocol identifiers, ABI or
dependency requirements changed; PHP remains `^8.5`.

## Validation scope

PHP SDK tests, Android JVM unit tests and the Android instrumented notification
tests (API 35 emulator: Keystore sealing, per-account request, dismissed
no-credential action) pass. iOS sources and XCTests (`NativeCapabilitiesParityTests`)
were not compiled on Linux and need Mac validation.
