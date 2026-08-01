# Runtime performance and recovery

Pam keeps application code unchanged while production builds select strict,
compiled runtime paths.

See the [capability cookbook](examples.md#scheduler-and-profiling) for a
copyable scheduled-work example with priority, coalescing and cancellation.

## Modes

Set `PAM_NATIVE_MODE` to:

- `development`: runtime diagnostics and flexible compiler checks;
- `production`: strict compilation and profiling disabled in the hot path;
- `benchmark`: strict compilation with measurements enabled.

`PAM_NATIVE_STRICT=1` enables production compiler checks during development.
Strict compilation rejects dynamic component properties and runtime code
evaluation that would prevent reliable dependency analysis.

## Scheduler

All framework-owned deferred work can use the bounded scheduler:

```php
$task = Scheduler::schedule(
    fn (CancellationToken $token) => $feed->prefetch($token),
    TaskPriority::Background,
    coalesce: 'feed.prefetch',
);
```

Priorities are integer-backed and deterministic. Coalescing cancels obsolete
work before execution. `drain($budgetMs)` yields when its monotonic frame budget
is exhausted. The queue is bounded to prevent unbounded memory growth.

Runtime render requests use one coalesced render task. Reentrant mutations set a
new render generation rather than nesting renderer calls.

## Granular dependencies

Reads from component local state and Pam Store properties are recorded against
the component currently rendering. Writes invalidate only subscribers. Clean
components retain their previous element subtree, including descendant
lifecycle state. Components using untracked mutable public fields retain the
legacy full-render behavior for compatibility.

## Compiled metadata

Component constructor and prop reflection is performed once and cached as a
factory/schema fast path. Subsequent mounts use direct constructor invocation,
cached prop metadata and direct public-property access. Production compilation
emits versioned cache metadata and invalidates older cache formats.

Run `vendor/bin/pam-native-optimize src build/pam-cache` during a production
build to compile every `.pam.php` component, emit a SHA-256 manifest and create
a relocatable `pam-preload.php`. Loading that file primes OPcache and eagerly
generates constructor factories plus prop schemas before the first application
frame; no template parsing or component reflection remains on the startup hot
path.

## Profiling

`Profiler` retains a bounded 512-span timeline containing:

- `php.render`;
- `php.encode`;
- `scheduler.task`;
- duration, timestamp and integer priority metadata.

Android continues to publish decode/mount `Trace` sections, frame metrics,
startup macrobenchmarks and Baseline Profile journeys. iOS renderer metrics are
available to the existing runtime callback and Instruments.

## Failure containment

The runtime publishes a throttled atomic checkpoint after a confirmed native
commit. Invalid commits never replace `lastFrame`, so the host preserves the
last known-good native hierarchy. Failures receive stable fingerprints and a
consecutive-failure counter; three consecutive failures enter safe mode and
prevent restart-loop policy from being mistaken for a healthy runtime.

An invalid incremental patch is handled before it becomes a visible failure:
the encoder keeps PHP component identity and cached subtrees, discards only its
previous wire snapshot and immediately emits one complete tree. Android and iOS
do not open an error overlay for this recoverable path. The C ABI exposes the
precise last commit error, and both native bridges include it in development
logs. A complete-tree rejection remains visible and never advances the
checkpoint.

Stores, navigation and component-restorable state keep their existing atomic
checkpoints. The checkpoint intentionally stores only frame identity, not the
binary tree, because the native renderer already owns the last committed tree.

## Gates

CI runs:

- PHP SDK and lifecycle contracts;
- deterministic 1,000-frame tree fuzzing;
- a 1,001-node cold/steady encoder budget;
- Rust tests, Clippy and a release-mode engine performance gate;
- Android unit, instrumented API 26/36 and macrobenchmark projects;
- iOS simulator tests.

Override local performance thresholds with `PAM_PERF_FIRST_FRAME_MS` and
`PAM_PERF_STEADY_FRAME_MS`. The native engine gate can be reproduced with
`cargo run --release -p pam-native-engine --example benchmark -- --check`;
its ceilings are configurable through `PAM_PERF_ENGINE_FULL_TREE_NS` and
`PAM_PERF_ENGINE_PATCH_NS`. CI defaults deliberately allow shared-runner
variance; physical-device macrobenchmark budgets remain the source of truth
for startup, frame timing and memory.
