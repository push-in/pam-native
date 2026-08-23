# Mobile benchmark contract

The comparison harness accepts React Native as the required public baseline and
optional Flutter and platform-native reports. A matrix is emitted only for
measurements present in every report, preventing selective claims:

```bash
python3 benchmarks/mobile/compare.py \
  --pam evidence/pam --react-native evidence/react-native \
  --flutter evidence/flutter --native evidence/platform-native \
  --output evidence/framework-matrix.md
```

The Android host contains a `:macrobenchmark` module with three identical,
automation-friendly scenarios:

1. cold startup from clean application state until `reportFullyDrawn()`;
2. 30 keyed property patches, including memory plus decode/mount trace sections;
3. repeated flings through 10,000 recycled rows.

Run Pam Native:

```bash
pam mobile benchmark path/to/project
```

The benchmark module is a separate instrumented application. Gradle installs
the minified `benchmark` target and its test APK together, so the measurement
never falls back to an already-running `.debug` application. The command fails
when the target cannot be installed or discovered.

Generate the startup Baseline Profile independently with:

```bash
pam mobile profile path/to/project
```

An equivalent React Native/New Architecture app must expose the accessibility
targets and payloads declared in `contract.json`. A Nitro comparison should add
the same native round-trip operation to both apps; Nitro Modules is not itself a
UI renderer, so its module-call latency must not be confused with list or
startup performance.

Compare the generated AndroidX JSON reports:

```bash
python3 compare.py \
  --pam results/pam \
  --react-native results/react-native \
  --output results/comparison.md
```

The comparison validates both reports against `contract.json` and refuses to
produce a table if either implementation omits a required scenario or metric.
It publishes every shared median, P50, P95, P99 and maximum supplied by
AndroidX, so tail-frame regressions cannot be hidden behind a good median.

Block a release when startup, mount, memory or tail-frame latency regresses:

```bash
python3 gate.py --report results/pam
```

The checked-in `budgets.json` is intentionally stricter than a generic
"benchmark completed" check: absent metrics fail, and list P95/P99 are gated
independently so a good median cannot hide visible jank.

Use a physical device, release-like non-debuggable targets, a stable thermal
state and the same compilation mode. Keep raw JSON and Perfetto traces as CI
artifacts. Record APK/AAB size separately because it is a build artifact rather
than a runtime metric; the CI release gate reports it alongside these results.

`REFERENCE.md` records the latest physical-device Pam Native baseline and its
remaining tail-latency caveat.

## Verifiable evidence

Create and immediately verify an integrity manifest before publishing or
uploading a result directory:

```bash
python3 benchmarks/mobile/evidence.py results/pam 1
python3 benchmarks/mobile/evidence.py results/pam 1 --verify
```

The manifest hashes every AndroidX report, Perfetto trace, log, and other result
artifact recursively. It separately hashes `contract.json` and `budgets.json`,
so changing the benchmark scenario or its release limits invalidates existing
evidence. Suite IDs are sequential integers: `1` for a PAM baseline and `2` for
a framework comparison.
