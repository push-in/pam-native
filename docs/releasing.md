# Releasing Pam Native

Application releases use one fail-closed entry point:

```bash
pam release --platform android
pam release --platform ios
# macOS only; produces both platform packages:
pam release --platform all
```

The command runs Native doctor and plugin certification before validating
signing and producing checksummed release artifacts. It never creates signing
credentials or uploads to a store. CI/store adapters consume the resulting
`dist` metadata and retain rollout authority.

## Release gate

1. Run PHP SDK tests and lint.
2. Run the Rust workspace tests.
3. Compile Android unit and instrumented-test sources.
4. Execute Android instrumented tests on API 26 and the current target API.
5. Run Swift Package tests on a current Xcode installation.
6. Exercise denied, blocked and limited permissions on physical devices.
7. Exercise push foreground delivery, background opening and cold-start deep
   links with APNs and FCM.
8. Validate recovery after rotation, backgrounding and process death.
9. Run `python3 scripts/protocol-parity.py` and `git diff --check`. The parity
   gate treats PHP as the identifier authority, requires exact Kotlin
   NodeKind/EventKind/PropKey coverage, exact Swift NodeKind/EventKind coverage,
   and rejects unknown or renamed numeric Swift property constants.
10. Update changelog, versions and migration notes together.

## Compatibility contract

- Protocol additions are append-only.
- Existing positional PHP parameters retain their order.
- Status/type/kind values are sequential integer enums starting at one.
- Optional host integrations fail with actionable errors.
- PHP, Rust, Android and iOS artifacts ship matching protocol definitions.

The Swift `PamConstants.imageProgressiveRendering` spelling remains as a
deprecated source-compatible alias. New code uses the authority-aligned
`imageProgressiveRenderingEnabled`; both resolve to property ID `200`.

## Publish artifacts

- Composer package.
- Rust runtime libraries for Android and Apple targets.
- Android host template and plugin API.
- Swift package/XCFramework.
- Checksums, changelog, migration notes and platform support matrix.

The tag workflow builds every published artifact twice before provenance
attestation and requires byte-for-byte equality. iOS is archived directly from
the tagged Git tree with a stable prefix. Android renderer and PHP SDK tarballs
use sorted paths, the tag commit epoch, numeric root ownership and header-free
gzip output. The Android plugin API is preserved, its isolated Gradle target is
cleaned and rebuilt, and the resulting AAR must match the preserved bytes. A
mismatch stops publication before checksums, package budgets or release upload
can bless nondeterministic bytes.

Each successful comparison also emits a bounded schema 1 reproducibility report
with sequential integer artifact/result codes, exact byte count and SHA-256.
The report is provenance-attested beside the artifact. After all jobs upload and
the release job downloads them, the offline verifier re-hashes all four
artifacts and rejects missing, stale, altered, mismatched or wrong-platform
evidence before GitHub Release creation.

The distributable production bootstrap is available at
`packages/native/resources/templates/production-capabilities.php.stub`.
