# PAM Native ecosystem architecture

This document is the release contract for the official PAM Native ecosystem.
An official package is not a PHP alias for a core module. It owns a bounded
capability, its typed PHP API, native implementation, lifecycle behavior,
permission flow, test doubles, integration tests, example, compatibility
matrix, changelog, CI, and signed release evidence.

## Package boundaries

| # | Package | Owned capability | Native surface | External certification gate |
|---:|---|---|---|---|
| 1 | `pushinbr/pam-native-firebase` | Firebase app configuration, Analytics, Crashlytics, Remote Config, Messaging adapters | Kotlin + Swift | Firebase projects and APNs credentials |
| 2 | `pushinbr/pam-native-auth` | Hardware-backed credential vault and PKCE challenge generation for OAuth/OIDC clients | PHP + Kotlin + Swift | Physical devices and provider-specific authorization flows |
| 3 | `pushinbr/pam-native-payments` | One-time wallet/card payment presentation and result normalization | PHP + Kotlin + Swift | PSP sandbox accounts, Apple Pay and Google Pay merchant approval |
| 4 | `pushinbr/pam-native-subscriptions` | StoreKit/Play Billing products, purchases, restore and entitlement verification | PHP + Kotlin + Swift | App Store Connect and Play Console products |
| 5 | `pushinbr/pam-native-maps` | Native map view, camera, markers, user location, gestures and normalized map events | PHP + Kotlin + Swift views | Maps API keys and physical-device rendering |
| 6 | `pushinbr/pam-native-media` | Local media metadata probing and bounded image/video thumbnail generation | PHP + Kotlin + Swift | Representative device media libraries |
| 7 | `pushinbr/pam-native-video` | Native playback view, controls, looping, volume, seeking, resize modes and progress events | PHP + Kotlin + Swift views | Physical-device playback and platform codec matrix |
| 8 | `pushinbr/pam-native-background-transfer` | Resumable uploads/downloads, constraints, retries and progress recovery | PHP + Kotlin + Swift | OS background execution on physical devices |
| 9 | `pushinbr/pam-native-realtime` | WebSocket channels, reconnect, heartbeats and bounded event backpressure | PHP + Kotlin + Swift | Network fault and long-running soak tests |
| 10 | `pushinbr/pam-native-sync` | Local-first mutation log, conflict policies, delta pull and retry orchestration | PHP + Kotlin + Swift storage adapters | Multi-device conflict suite |
| 11 | `pushinbr/pam-native-share-extension` | Incoming shares and extension-to-app inbox hand-off | PHP + Kotlin + Swift extension | Apple signing and inter-app device tests |
| 12 | `pushinbr/pam-native-widgets` | Timeline/state bridge for Android widgets and WidgetKit | PHP + Kotlin + Swift extension | Widget gallery/device tests |
| 13 | `pushinbr/pam-native-live-activities` | Activity lifecycle, attributes and push-token bridge | PHP + Swift extension | APNs and supported iPhone hardware |
| 14 | `pushinbr/pam-native-intents` | App Intents, shortcuts and Android intent/deep-link contracts | PHP + Kotlin + Swift | Siri/Assistant and device tests |
| 15 | `pushinbr/pam-native-health` | Typed HealthKit/Health Connect reads, writes and authorization | PHP + Kotlin + Swift | Entitlements and physical health-data devices |
| 16 | `pushinbr/pam-native-bluetooth` | BLE scan, connect, GATT, notifications and restoration | PHP + Kotlin + Swift | Real peripherals and background tests |
| 17 | `pushinbr/pam-native-nfc` | NDEF read/write and platform session lifecycle | PHP + Kotlin + Swift | NFC-capable physical devices |
| 18 | `pushinbr/pam-native-scanner` | Camera barcode scanning with normalized symbology and results | PHP + Kotlin + Swift views | Camera devices and representative barcodes |
| 19 | `pushinbr/pam-native-observability` | Spans, structured logs, crashes, metrics, bounded batching and exporter adapters | PHP | Backend ingestion projects |
| 20 | `pushinbr/pam-native-feature-flags` | Typed flags, snapshots, targeting context and provider adapters | PHP + Kotlin + Swift cache | Provider projects for official adapters |
| 21 | `pushinbr/pam-native-testing` | Fake bridge, deterministic scheduler, fixtures, assertions and native harnesses | PHP + Kotlin + Swift | None; entirely certifiable in CI |
| 22 | `pushinbr/pam-native-devtools` | Event timeline, state snapshots, network diagnostics, performance marks, redaction and JSON export | PHP | None; entirely certifiable in CI |
| 23 | `pushinbr/pam-native-plugin-kit` | Scaffolding, manifest validation, IDL codegen and compatibility certification | PHP + CLI templates | None; entirely certifiable in CI |
| 24 | `pushinbr/pam-native-laravel-sync` | Laravel delta API, mutation ingestion, auth, conflicts and retention | Laravel package | Supported Laravel/database matrix |

## Mandatory release gates

Every repository must pass the following gates before its first stable tag:

1. Strict Composer metadata, PHP 8.4+, PHPStan level 9, formatting and unit tests.
2. Generated binary-wire contracts with sequential integer enums for every
   status, type, state, kind, category or discriminator.
3. Android lint, unit tests, instrumentation tests and release assembly on the
   documented API/ABI matrix.
4. Swift Package Manager build and tests on every documented iOS minimum.
5. Contract tests proving PHP, Kotlin and Swift encode identical payloads.
6. Permission denial, cancellation, timeout, process restart and cleanup tests.
7. A runnable example and an upgrade/compatibility test against supported PAM
   Native releases.
8. Reproducible CI artifacts, SBOM, dependency review, changelog and provenance.
9. Documentation that separates CI-certified behavior from vendor-account,
   signing, store or physical-device certification.

No package may advertise a certification gate as passed without retaining the
corresponding CI run or external test evidence.
