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

```php
$decision = UpdateVerifier::evaluate(
    manifestJson: $manifest,
    signatureBase64: $signature,
    publicKeyBase64: $_ENV['PAM_OTA_PUBLIC_KEY'],
    currentBuildIdentifier: $activeBuild,
    rolloutBucket: $installationBucket,
);

if ($decision->approved() && UpdateVerifier::verifyBundle($download, $decision->manifest)) {
    $slots = new UpdateSlotManager($applicationData.'/updates');
    $slots->stage($download, $decision->manifest);
    $slots->activate();
}
```

Activation uses a private locked directory, stages on the destination
filesystem, hashes again after copying, atomically renames the candidate, and
keeps one prior slot. If the health/readiness gate fails on the next launch,
`rollback()` restores that prior bundle and quarantines the failed slot.
