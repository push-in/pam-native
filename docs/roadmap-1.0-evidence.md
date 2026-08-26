# PAM Native 1.0 roadmap evidence

This index maps the public Singularity and Event Horizon promises to concrete
runtime boundaries, tests, release gates, and retained artifacts. It is an
audit aid, not a substitute for executable evidence: a row is release-ready
only when its named gate runs against the immutable release candidate.

Evidence strength uses three integer-backed states in the audit process:

1. **Contract** — public types, schemas, and compatibility rules exist.
2. **Executable** — deterministic tests or static gates exercise the contract.
3. **Release-certified** — Android/iOS or package release workflows retain
   evidence for the exact immutable candidate.

## Runtime and authoring

| Capability | Contract and implementation | Executable/release evidence | State |
| --- | --- | --- | ---: |
| ABI, wire protocol, and capability negotiation | `packages/native/src/ProtocolHandshake.php`, `crates/pam-native-protocol`, Android/iOS `ProtocolHandshake` | PHP SDK suite, Rust protocol suite, Android and iOS handshake tests, protocol parity gate | 3 |
| Typed IDL and native contracts | `packages/native/src/Bridge/IdlCompiler.php`, `ContractCompiler.php` | deterministic PHP/Kotlin/Swift generation in `packages/native/tests/singularity.php` | 2 |
| UI compiler, IR, source maps, and language 2 | `TemplateCompiler`, `StyleIrCompiler`, `PamPhpCompiler`, UI language 2 contracts | PHP SDK fuzz and compiler tests, PHPStan level 9, Android/iOS renderer certification | 3 |
| Freeze, preload verification, and tree shaking | `packages/native/bin/pam-native-optimize`, `Internal/PamPhpPreloader.php` | SDK optimize/preload regressions and clean community build gate | 3 |
| Incremental renderer and retained Fiber lanes | Rust `fiber.rs`, `scheduler.rs`, `reactive.rs`, transactional engine generations | Rust engine suite, frame fuzz, performance-profile gate | 3 |
| 60/90/120/144 Hz scheduling | Rust scheduler/performance modules and adaptive iOS display scheduling | p50/p95/p99 budget tests and release performance contracts | 3 |
| Signals, computed state, effects, and worklets | PHP `Signals`, `Worklets`; Rust reactive engine; Kotlin/Swift worklet hosts | PHP/Rust suites plus Android/iOS worklet tests | 3 |
| CSS-to-native, layout, queries, and tokens | Style compiler family, Rust layout engine, native environment bridges | style conformance, protocol parity, Android/iOS source and visual gates | 3 |
| Motion and interaction states | `MotionPreset`, native motion policy and worklet animators | PHP renderer tests, Android motion/worklet tests, iOS build certification | 3 |
| Virtual lists and grids | `VirtualizedList`, `VirtualGrid`, Rust virtualization, native recycler/list hosts | PHP/Rust suites, Android virtual-scroll tests, cross-platform source gates | 3 |
| Navigation | typed PHP navigation surface plus Android/iOS native navigation hosts | navigation performance suite, Android instrumentation, iOS source/build gate | 3 |

## Data, platform, and operations

| Capability | Contract and implementation | Executable/release evidence | State |
| --- | --- | --- | ---: |
| Local-first records and encrypted journal | `LocalFirst` and `Store` namespaces | Singularity SDK tests, encryption and deterministic conflict regressions | 2 |
| Offline mutation and independent sync | `Sync/OfflineMutationQueue.php`; standalone optional sync packages | ecosystem latest/lowest dependency graphs and package suites | 3 |
| Socket-free internal request/response | `InternalHttp/LocalTransport.php` | SDK assertion that the transport opens no network socket | 2 |
| Plugin registry, trust, and capability grants | `PluginManager`, registry/trust types, manifest schema | CLI autolink/audit tests and full independent plugin ecosystem matrix | 3 |
| Plugin independence | Composer/Rust dependency-law validators | release-blocking ecosystem inventory and latest/lowest graphs | 3 |
| Hybrid native and GPU surfaces | native view registry plus optional Canvas/GPU/3D packages | Android/iOS plugin compilation and ecosystem package suites | 3 |
| Stateful hot reload and deterministic DevTools | hot reload coordinator/snapshots, replay timeline, native overlays | replay/state tests, latency contracts, Android/iOS source/build gates | 3 |
| Signed OTA slots and rollback | signed manifests, update verifier/slot manager, Android/iOS active installers | PHP verifier tests, Android/iOS boot installer tests and release source gates | 3 |
| Brownfield embedding | Android `PamNativeLauncher`, iOS `PamNativeViewController` | Android plugin API archive and iOS package build in every release | 3 |
| Reproducible build and distribution | CLI build/package/release, deterministic archives, checksums and budgets | release workflow, reproducibility reports, SBOM and provenance attestations | 3 |
| Mandatory build cleanup | CLI cleanup lifecycle and allowlisted repository cleanup scripts | unconditional `if: always()` steps after CI/release uploads and clean-room runs | 3 |
| Observability and production audit | diagnostics, profiler, audit command, redacted evidence | production audit tests, accessibility aggregation, retained release evidence | 3 |
| Comparative benchmarks | same-device contract for PAM, React Native, Flutter, and native | benchmark parser/statistical tests and physical-device evidence validator | 2 |
| Community first run | generated server/mobile/UI starters and self-repairing doctor path | release-blocking clean `pam init` plus `pam dev`, emulator launch, Logcat and screenshot | 3 |

## Honest benchmark boundary

The comparative benchmark row deliberately remains state 2 in source control.
The harness, schema, statistics, thermal/build-mode checks, and budgets are
executable, but a universal performance claim requires fresh same-device raw
evidence for every compared framework. Release certification may attach that
evidence; the repository must never convert a harness-only result into a
marketing multiple.

## Independence invariant

PAM Native remains a client runtime. It does not require PAM HTTP, Laravel, or
any sibling plugin. PAM HTTP does not require PAM Native. Cross-surface
integration is optional application composition over versioned public
contracts, and every plugin matrix run rejects sibling plugin dependencies.
