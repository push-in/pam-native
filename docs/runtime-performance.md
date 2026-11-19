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
| Package Footprint | Every release artifact, including an isolated clean rebuild of the Android plugin API AAR, is built twice and compared byte-for-byte; strict reproducibility and size/digest reports are persisted, independently rehashed, provenance-attested beside explicit iOS, Android renderer, plugin API and PHP SDK byte ceilings, and reverified after artifact download before publication. |

The worklet API and its safety limits are documented in
[Platform runtime](platform-runtime.md#worklet-bytecode); image pipeline policies
and cache behavior are documented in [Native media cache](media-cache.md).

## Native tree encoder

On Android and iOS the hosts register `pam_native_encoder_*`, a C++ port of
`Internal\TreeEncoder` (`android/app/src/main/cpp/pam_tree_encoder.cpp`,
compiled into the iOS bridge as well). `TreeEncoder` delegates to it
automatically: the same node ids (XXH3 of the identity path), the same
property bytes (including template-encoded properties), the same full frames,
the same patch operations in the same order, the same callbacks and the same
errors. Apps change nothing. The PHP encoder remains the reference and the
fallback (`PAM_NATIVE_ENCODER=php` forces it).

`scripts/native-encoder-test.sh` builds the encoder as a host PHP extension
and runs `packages/native/tests/native_encoder_equivalence.php` (full frames,
patches, random trees with reused subtrees, DOM ids, holes, events, binary
and float values, refresh hosts, template-encoded properties, identity
collisions and invalid values: byte-for-byte equal) plus the patch-replay
fuzz `encoder_patches.php` with the native encoder.

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

The same retained dependency graph powers the public
[`Signals`](signals.md) API. Signals add lazy `computed()` values, automatically
tracked effects and nested batching while preserving component-level subtree
reuse. No polling, global dirty flag or per-frame PHP callback is introduced.

### Granular re-render of ancestors

A component dirty only because of a descendant does not run its template
again. When a component's own inputs are unchanged (own state snapshot, no
tracked read or handler marked it, no global invalidation) but a descendant
is dirty, it keeps its last element and renders again only the children on
the path to that descendant. Each new child element is placed exactly where
the owner's template placed the previous one: the call site's `class`, `key`
and style attributes are applied again (with the same memoized copies) and
only the elements on the path from the owner's root are copied, so every other
subtree keeps its identity and its encoded frame. Moving state into a small
child component therefore saves the parents' templates, as in React or Vue:
typing in a composer component inside a sheet of a screen renders the
composer alone.

The result is always the tree a full render would produce. The owner falls
back to its full render whenever a child cannot be placed again exactly:

- the child was given as slot content to another component (it lives in that
  component's tree);
- the child is a lazy row of a `VirtualizedList`/`VirtualGrid` (built by the
  list window), or portal content;
- the child was built in PHP code (`render()` returning elements) or by a
  component tag with native events, bindings or `p-ripple` (not a compiled
  `.pam` tag);
- the owner's tree no longer holds the child's element exactly once.

`#[AlwaysRender]` children of an updated owner render with it, as on a full
render. Lifecycle is unchanged: children that are not rendered again stay
mounted with their subtrees; what a re-rendered child drops unmounts and what
it adds mounts. A child that throws propagates to the owner as from its
render: the owner's `failed()`/`fallback()` boundary applies and its recovery
is shown, with its components kept mounted (a change in the failing child
renders it again).

The profiler records a granular update as a `component.patch` span (with
`component` metadata) instead of `component.render`.

Fields written outside handlers (hooks, timers, callbacks) count as the
component's own inputs. Fields `render()` never reads can opt out with
`#[Transient]` (see
[component runtime](component-runtime.md#state-snapshot-and-transient-fields)).
`Pam\Native\Internal\GranularRender::enabled(false)` turns it off (every
ancestor renders again), e.g. to compare.

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

## Sliced mount (Android)

Opening a screen used to build every view of the commit inside one frame: a
`ScrollView` with ~50 rows cost ~40 ms of UI thread on a Galaxy S10. Android
now builds, inside the commit, only what the screen shows, and the rest in
the frames that follow:

- A commit that creates 40 or more nodes resolves its new nodes under a
  vertical `ScrollView` once their frames are known. A subtree whose frames
  all lie outside the scroll's viewport (plus 64 dp, which covers shadows and
  a frame of fling) waits; everything that intersects it is built before the
  commit's frame draws, exactly as before.
- Waiting subtrees are built by mount slices: a Choreographer callback per
  frame, after input and before the traversal, spending 6 ms less whatever a
  commit already took in that frame (at least one subtree per frame), in tree
  order. Each slice first builds every waiting subtree that scrolled into, or
  within a quarter viewport of, the visible band, regardless of the budget. A
  pre-draw check builds and redraws if a layout pass moved an offset onto
  waiting content, so a row is never drawn missing.
- Nothing about layout or scrolling changes: views are placed at absolute
  engine frames and the scroll content extent comes from engine frames, so a
  missing view leaves no gap and moves nothing. Views keep their logical
  order among their host's children.
- Later commits stay ordered: properties and frames of waiting nodes are kept
  in the node state and applied when the view is built; a waiting subtree
  laid out into view, or under a scroll that receives `contentOffset` or a
  `scrollRequest` in that commit, is built by that commit; a move into or
  out of a waiting subtree builds it first; a removal drops it unbuilt.
  `on:layout` is reported from engine frames, once, as before.
- Only plain content waits: `View`, `Column`, `Row`, `Text`, `Image`,
  `ImageBackground`, `Pressable` and `Spacer` nodes without transforms,
  drags, sticky headers, animations or transitions, intersection/route/
  resize/outside-press observers, text-layout events, focus or key handling,
  media bindings, keyboard progress, shared-element tags, live regions or a
  `nativeRef` (`ViewRef` measures a laid-out view),
  under a vertical scroll that is not paged and has no `anchorToEnd`,
  `maintainVisibleContentPosition`, `scrollTargetTestId` or pending
  non-zero `contentOffset`. Anything else is built in the commit.

The layout pass also lays each new view out once: a host whose frame changed
no longer re-lays out the children that the same pass lays out right after.

`PamSlicedMountInstrumentedTest` mounts 60 rows of `Pressable` + `Image` +
`Text` on the CI emulator: the commit's frame must stay within 24 ms (a debug
build there is ~1.6x slower than a release build on the S10), every slice
frame within 16 ms, and the worst frame under half that of building
everything at once; the final views, geometry, texts and order must equal
the full mount's.

Perfetto/systrace sections: `PamCommit.slicedMount`, `PamMount.slice`, and,
only while a trace is recording, `PamMount.create.<KIND>`,
`PamMount.attach.<KIND>`, `PamMount.props.<KIND>`, `PamMount.events.<KIND>`,
`PamMount.text`, `PamMount.image`, `PamMount.background`, `PamMount.update`,
`PamMount.remove` and `PamMount.move` inside `PamCommit.mutations`.

## Profiling

`Profiler` retains a bounded 512-span timeline containing:

- `php.render`;
- `php.encode`;
- `component.render` (a component ran its template) and `component.patch`
  (it kept its render and updated only the paths to dirty descendants), with
  `component` metadata;
- `scheduler.task`;
- duration, timestamp and integer priority metadata.

Android continues to publish decode/mount `Trace` sections, frame metrics,
startup macrobenchmarks and Baseline Profile journeys. iOS renderer metrics are
available to the existing runtime callback and Instruments.

In Android and iOS development builds, every accepted hot-reload version also starts a
monotonic device-side measurement immediately before its bundle download. The
measurement ends at the first committed native frame, or at the first native
runtime failure. DevTools records it as integer diagnostic kind `6` (`LOAD`),
including duration, failure outcome and downloaded bundle bytes. A newer
version supersedes an unfinished sample and a completed sample cannot be
reported twice. A separate bounded 64-sample window exports successful count,
failure count/rate, nearest-rank successful p95, its configured budget and the
budget result in the diagnostics JSON. The overlay shows the same p95 and
failure totals. Set `hotReloadP95BudgetMs` in `android/pam-native.properties`
to change Android's default 1,000 ms development-device budget; iOS uses the
same 1,000 ms contract. The iOS debug host connects only to the loopback HTTP
endpoint, streams each response under its protocol limit, refuses redirects,
activates into `Library/Caches/pam/dev`, and retains only the active version.
This measures the
full device-visible reload path rather than only transport or PHP evaluation
time; hosted device distributions remain a separate release-evidence gate.

Export a physical-device snapshot with `pam mobile android:diagnostics`, then
gate it offline with:

```bash
scripts/check-hot-reload-evidence.php pam-diagnostics.json 20 1000
```

The verifier accepts Android (`platformCode: 1`) and iOS (`platformCode: 2`)
diagnostics contracts, checks internally
consistent integer counts/rates and budget result, requires the requested
sample floor, rejects every failed reload and rejects p95 above both the
snapshot's configured budget and the independent CI ceiling. Inputs are
limited to 1 MiB before reading and symbolic links are refused.

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

Android development bundles use the same last-known-good rule. The host parses
and bounds the complete `PNA1` payload in a sibling staging directory, rejects
duplicate or unsafe paths, requires `index.php`, and only then swaps the active
version. A preserved sibling backup is restored if activation fails or a prior
activation was interrupted; malformed or truncated bundles remove staging
without deleting the active PHP application.

iOS now consumes the identical `PNA1` file/count/size/path contract through a
Foundation-only parser. It additionally rejects invalid UTF-8, symbolic-link
destinations and trailing bytes, writes files atomically inside a sibling
staging directory, and applies the same active/previous rollback protocol.
The generated debug host polls only an explicit loopback endpoint with bounded
timeouts, refuses redirects, streams responses under their status/bundle
limits, cancels the session during runtime shutdown and retains only the active
cache version. Its accepted-version timestamp spans bundle transfer,
transactional activation, PHP reload and the first committed native frame; the
same bounded p95/failure evidence contract is exported on Android and iOS.

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
