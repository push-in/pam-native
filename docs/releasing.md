# Releasing Pam Native

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
9. Run `git diff --check` and verify protocol values remain sequential.
10. Update changelog, versions and migration notes together.

## Compatibility contract

- Protocol additions are append-only.
- Existing positional PHP parameters retain their order.
- Status/type/kind values are sequential integer enums starting at one.
- Optional host integrations fail with actionable errors.
- PHP, Rust, Android and iOS artifacts ship matching protocol definitions.

## Publish artifacts

- Composer package.
- Rust runtime libraries for Android and Apple targets.
- Android host template and plugin API.
- Swift package/XCFramework.
- Checksums, changelog, migration notes and platform support matrix.

The tag workflow builds every source archive twice before provenance
attestation and requires byte-for-byte equality. iOS is archived directly from
the tagged Git tree with a stable prefix. Android renderer and PHP SDK tarballs
use sorted paths, the tag commit epoch, numeric root ownership and header-free
gzip output. A mismatch stops publication before checksums, package budgets or
release upload can bless nondeterministic bytes.

The distributable production bootstrap is available at
`packages/native/resources/templates/production-capabilities.php.stub`.
