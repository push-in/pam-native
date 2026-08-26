# Mandatory build hygiene

PAM Native builds leave only declared deliverables behind. Regenerable build
trees and project-local tool caches are not release artifacts and must be
removed after every local, CI and release build, including failed builds.

## Command contract

| Command | Retained | Removed on exit |
| --- | --- | --- |
| `pam mobile build` | Checksummed APK or simulator `.app` in `dist` | Android `app/build`, root build, project Gradle caches/daemons; iOS `DerivedData` |
| `pam mobile package` | Checksummed APK/AAB or IPA and release metadata in `dist` | Gradle/Xcode intermediates and export workspaces |
| `pam dev` / `pam mobile dev` | Source, dependencies and explicit evidence | All regenerable session build outputs when the session exits |
| CI/release | Uploaded final release assets and bounded evidence | Runner build trees in an unconditional final step |

Cleanup is fail-closed and project-scoped. It never removes source, `vendor`,
application data, credentials, screenshots, release evidence or `dist`. A
cleanup failure fails a successful build; when the build itself failed, PAM
preserves that original error and also reports the cleanup failure.

Official packages and contributors must follow the same invariant. A workflow
that builds native code without an unconditional cleanup step is not eligible
to publish a release.
