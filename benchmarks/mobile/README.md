# Mobile benchmark contract

The Android host contains a `:macrobenchmark` module with three identical,
automation-friendly scenarios:

1. cold startup from clean application state until `reportFullyDrawn()`;
2. 30 keyed property patches, including memory plus decode/mount trace sections;
3. repeated flings through 10,000 recycled rows.

Run Pam Native:

```bash
pam mobile benchmark path/to/project
```

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
