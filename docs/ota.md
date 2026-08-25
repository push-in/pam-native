# Signed over-the-air bundles

PAM Native OTA is a runtime bundle update mechanism, not an application-store
bypass. Native code, permissions, entitlements, privacy declarations, and
plugin binaries still ship through Google Play or the App Store.

`SignedUpdateManifest` uses canonical JSON and sequential integer contracts for
channel and decision status. `UpdateVerifier` requires:

- a detached Ed25519 signature from the pinned application public key;
- the exact lowercase SHA-256 of the bundle;
- ABI and protocol compatibility with the installed native host;
- only capabilities already present in that host;
- a deterministic rollout bucket from `0` through `9,999`.

Manifests are capped at 64 KiB and bundles at 256 MiB. Unknown fields,
non-canonical JSON, invalid keys, mismatched bytes, or incompatible capabilities
fail closed before staging.

Build the platform-neutral `PNA1` payload from the exact PHP, Composer and asset
tree that will execute on device:

```bash
pam update:bundle . artifacts/app-1.0.1.pna
```

The command prints the SHA-256 used as `bundleSha256`. It refuses symlinks,
unsafe paths, more than 10,000 files, individual files over 8 MiB, and aggregate
payloads over 256 MiB. Android and iOS use the same bounded decoder.

```php
$decision = UpdateVerifier::evaluate(
    manifestJson: $manifest,
    signatureBase64: $signature,
    publicKeyBase64: $_ENV['PAM_OTA_PUBLIC_KEY'],
    currentBuildIdentifier: $activeBuild,
    rolloutBucket: $installationBucket,
);

if ($decision->approved() && UpdateVerifier::verifyBundle($download, $decision->manifest)) {
    $slots = UpdateSlotManager::forRuntime();
    $slots->stage($download, $decision->manifest);
    $slots->activate();
}
```

Activation uses a private locked directory, stages on the destination
filesystem, hashes again after copying, atomically renames the candidate, and
keeps one prior slot. On the next cold launch, Android and iOS independently
re-hash `active.bundle`, decode it into a content-addressed private release, and
start that entry. A malformed, truncated or mismatched active slot is
quarantined as `failed.*` and startup falls back to the store bundle.

The downloader and manifest endpoint are deliberately application choices.
They may be plain HTTPS, PAM HTTP, Laravel, a CDN, or another backend. The
native package imports no server package, and a server needs no native package;
the only shared boundary is the documented JSON and byte contract.
