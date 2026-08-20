# Runtime performance and recovery

Pam keeps application code unchanged while production builds select strict,
compiled runtime paths.

See the [capability cookbook](examples.md#scheduler-and-profiling) for a
copyable scheduled-work example with priority, coalescing and cancellation.

## Performance architecture

Pam treats performance as sixteen cooperating contracts rather than one
benchmark number:

| Pillar | Production path |
| --- | --- |
| Bridge V2 | Bounded binary batches, pooled ABI output buffers and append-only typed IDL avoid JSON and recycle native allocations. |
| Critical-frame execution | PNW1 worklets run from `ValueAnimator` on Android and `CADisplayLink` on iOS without re-entering PHP between frames. |
| Incremental renderer | Stable node identity and property/structure patches mutate only changed retained native views. |
| Display-aware scheduler | The physical 60/90/120 Hz refresh rate sets the commit deadline; priority, cancellation and coalescing protect input work. |
| Virtualization V2 | Variable extents, stable keys, velocity-biased overscan and logarithmic window lookup keep 100,000-item lists bounded. |
| Incremental layout | Dirty node/ancestor paths reuse retained frames and prune unchanged subtrees. |
| Text engine | Platform font advances feed a bounded Rust metrics cache; font changes remeasure only affected text nodes. |
| Image pipeline | Deduplicated requests, target-size downsampling, decoded-memory/disk LRU caches and generation-safe recycling stay off the UI thread. |
| PHP Runtime Turbo | Production strict mode, OPcache preload and compiled component metadata remove parsing and reflection from startup. |
| Aggressive codegen | Generated native view registries, property schemas and constructor factories replace hot-path discovery. |
| Fast paths | Paint-only patches skip layout, fixed text boxes skip intrinsic measurement and unchanged component subtrees retain their output. |
| Optimized native builds | Rust ThinLTO/O3, Android R8/section GC and Swift whole-module optimization are certified in release builds. |
| Memory control | Every queue/cache is bounded; pooled buffers, view dematerialization and platform pressure signals reclaim retained resources. |
| Performance Observatory | Bounded histograms export P95 stages, deadlines, retained bytes, buffer reuse, nodes and frame misses to both overlays. |
| Performance Contract | PHP/Rust budgets, 100,000-item iOS coverage, Android renderer tests, release builds and device macrobenchmarks gate regressions. |
| Package Footprint | Release archives are streamed through explicit iOS, Android renderer, plugin API and PHP SDK byte ceilings; schema-valid size/digest reports are persisted and provenance-attested beside them before upload. |

The worklet API and its safety limits are documented in
[Platform runtime](platform-runtime.md#worklet-bytecode); image pipeline policies
and cache behavior are documented in [Native media cache](media-cache.md).

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

## Incremental layout

Property patches mark only their changed node IDs and ancestor paths as dirty.
The Rust layout engine re-evaluates the affected container so flex, grid and
sibling displacement remain correct, then stops descending whenever a clean
subtree receives the same retained frame. Dirty subtrees are removed from the
previous map before calculation, preventing stale geometry when visibility
changes. Fixed-size text boxes additionally bypass intrinsic measurement for
text/font changes because their resolved geometry cannot change. Tests compare
incremental output byte-for-byte with a complete layout and require a deep
two-branch fixture to visit less than one quarter of its retained nodes.
Packaged-font advances include a bounded half-point platform guard so Android
hinting cannot create a native wrap that the retained Rust height did not
reserve; this remains deterministic and requires no measurement bridge call.

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

The Rust engine also receives the physical display refresh rate from
`DisplayManager` on Android and `UIScreen.maximumFramesPerSecond` on iOS. Every
successful commit is evaluated against the corresponding 60/90/120 Hz budget.
The bounded observatory exports measured frames, deadline misses, P95
decode/reconcile/layout/encode latency, coalesced commands, retained bytes and
zero-copy buffer reuse through the stable C ABI. Both DevTools overlays display
the same counters, so a deadline regression is visible without attaching a
platform profiler.

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
- iOS simulator tests, including the 100,000-item bounded-window performance
  contract, followed by an optimized Release build;
- sequential integer-coded package-size budgets for every published Native
  archive, enforced before provenance attestation and artifact upload.

Override local performance thresholds with `PAM_PERF_FIRST_FRAME_MS` and
`PAM_PERF_STEADY_FRAME_MS`. The native engine gate can be reproduced with
`cargo run --release -p pam-native-engine --example benchmark -- --check`;
its ceilings are configurable through `PAM_PERF_ENGINE_FULL_TREE_NS` and
`PAM_PERF_ENGINE_PATCH_NS`. CI defaults deliberately allow shared-runner
variance; physical-device macrobenchmark budgets remain the source of truth
for startup, frame timing and memory.

## Optimized native builds

Rust release engines use `-O3`, one codegen unit, ThinLTO, aborting panics,
disabled overflow checks and stripped symbols. Android release linkage adds
function/data sections, section garbage collection and safe identical-code
folding before R8 and resource shrinking. The release workflow compiles the
iOS package with the Release configuration, whole-module Swift optimization,
dead-code stripping and library-evolution metadata; CI builds that same
optimized configuration so a debug-only success cannot certify a release.
