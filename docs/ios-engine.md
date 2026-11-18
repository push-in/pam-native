# iOS engine: prebuilt per release

`pam mobile ios:prepare`, `ios:run` and `ios:build` link
`PamNativeEngine.xcframework`, the Rust engine built from the same
pam-native sources as the PHP package in `vendor/`. Since 1.52.0 every
release publishes it, so a Mac needs no Rust toolchain:

- Asset: `pam-native-ios-engine-<version>-<protocol>-<engine>.zip` and its
  `.sha256`, on the GitHub release `v<version>`. `<protocol>` and `<engine>`
  are the first 16 hex digits of the protocol and engine source
  fingerprints, so an asset is only used by the exact sources it was built
  from. `scripts/build-ios-engine.sh` builds it (device arm64, simulator
  arm64 and x86_64) in the release workflow.

## How the CLI picks an engine

1. The PAM runtime slot (`runtime/ios/<runtime>/`) when its engine marker
   matches the project's pam-native sources.
2. The engine cache for that key, when already filled.
3. Download the release asset, verify its SHA-256 (and a 256 MiB cap),
   unpack it into the cache and check the marker again.
4. Otherwise the error names both causes; a local rebuild of the slot
   (`cargo` with the iOS targets) stays the fallback.

Projects on different pam-native versions get different keys: they never
share or overwrite one engine.

## Cache and overrides

| Variable | Effect |
| --- | --- |
| `PAM_NATIVE_ENGINE_CACHE` | Cache directory. Default `~/Library/Caches/pam-native/engines` on macOS, `$XDG_CACHE_HOME/pam-native/engines` elsewhere. |
| `PAM_NATIVE_RELEASE_BASE_URL` | Base URL of the release assets (a mirror, or `file://` for an offline test). Anything but `https://` is refused unless `PAM_RELEASE_BASE_URL` is set too. |

Deleting the cache directory is always safe: the next `ios:*` command
downloads the engine again.
