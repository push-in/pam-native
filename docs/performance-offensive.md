# Performance offensive

PAM Native renders through the platform frame clock. Android batches mutations
on `Choreographer`; iOS uses `CADisplayLink` with the display's adaptive maximum.
The engine classifies 60, 90, 120, and 144 Hz displays and gives every frame a
matching deadline.

Reactive patches are incremental twice: the engine evaluates only worklets
whose signal dependencies changed, then visits only bindings indexed to those
worklets. A reusable numeric stack avoids an allocation per evaluation. Native
hosts coalesce pending batches at the next frame boundary.

## Reproduce the budgets

```bash
cargo run --locked --profile performance \
  -p pam-native-engine --example benchmark -- --check
```

The command measures throughput, p50, p95, p99, and peak commit latency for a
full tree and a dependency-scoped patch. Versioned defaults fail the process at
2 ms p99 for full commits or 1 ms p99 for patches. Override them only while
investigating with `PAM_NATIVE_FULL_P99_BUDGET_NS` and
`PAM_NATIVE_PATCH_P99_BUDGET_NS`.

For production-equivalent compilation:

```bash
scripts/build-performance.sh release
scripts/build-performance.sh pgo
```

`release` uses fat LTO and one codegen unit. `pgo` instruments the engine,
trains it with the canonical workload, merges the profile with
`llvm-profdata`, and rebuilds using that profile. CI runs the same benchmark
contract on every pull request, while Android Macrobenchmark and iOS evidence
suites retain frame-time, startup, memory, and long-run budgets.

## Profiling rule

Profile a release-like binary and optimize only measured hot paths. Compare the
same device, build type, fixture, thermal state, and sample count. A change is
accepted only when functional tests remain green and its p99 does not regress.
