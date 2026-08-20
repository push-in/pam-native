# Release package size budgets

PAM Native fails release packaging when a distributable crosses its explicit
compressed-size ceiling. Artifact codes are sequential integers:

1. iOS source archive — 16 MiB;
2. Android renderer archive — 128 MiB;
3. Android plugin API AAR — 4 MiB;
4. PHP SDK archive — 8 MiB.

Run the gate with one or more artifacts produced by the release workflow:

```bash
python3 benchmarks/package/gate.py \
  --artifact 3=dist/pam-native-android-plugin-api-0.2.1.aar \
  --artifact 4=dist/pam-native-php-0.2.1.tar.gz \
  --output dist/package-budget.json

python3 benchmarks/package/gate.py \
  --artifact 3=dist/pam-native-android-plugin-api-0.2.1.aar \
  --artifact 4=dist/pam-native-php-0.2.1.tar.gz \
  --verify-report dist/package-budget.json
```

The JSON report carries the actual and maximum byte counts, SHA-256 digest and
integer result code (`1` passed, `2` exceeded). Inputs must be non-empty regular
files, symlinks are refused, contract documents are bounded to 1 MiB and
artifacts to 512 MiB. Artifact hashing streams in 1 MiB chunks rather than
loading release packages into memory. `report.schema.json` publishes the strict
Draft 2020-12 report contract. Release jobs persist and provenance-attest each
report beside the package before both enter the downloadable artifact and
GitHub Release. The paired verifier rehashes the exact package bytes and
requires the report to match the current budget contract before attestation.
After all platform artifacts are downloaded into the release job, the same
consumer verifies all three reports and all four packages again; publication
cannot rely only on the producer job's pre-upload filesystem.
These are release safety ceilings, not device performance
baselines; startup, frame pacing and memory remain governed by the mobile and
iOS benchmark contracts.

## Reproducibility evidence

The paired reproducibility gate compares two independently created artifacts
while streaming both files, records the published artifact's size and SHA-256,
and uses integer result code `1` for identical bytes or `2` for a mismatch:

```bash
python3 benchmarks/package/reproducibility.py \
  --pair 3=dist/plugin-api-first.aar=build/plugin-api-second.aar \
  --output dist/plugin-api.reproducibility.json

python3 benchmarks/package/reproducibility.py \
  --artifact 3=dist/plugin-api-first.aar \
  --verify-report dist/plugin-api.reproducibility.json
```

`reproducibility.schema.json` defines the strict schema 1 contract. Artifact
codes share the sequential `1`–`4` package-budget enum. Reports and artifacts
must be regular non-symlink files; reports are limited to 1 MiB and artifacts
to 512 MiB. Every platform report is provenance-attested beside its package,
downloaded by the final release job and reverified against the exact bytes
before publication.
