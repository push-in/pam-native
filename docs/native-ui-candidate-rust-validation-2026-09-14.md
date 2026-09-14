# Native UI candidate — Rust validation

## Corrected 1.0.28 candidate: complete CI pass

[Run 34838564521](https://github.com/push-in/pam-native/actions/runs/34838564521)
completed successfully on exact revision
`e963ef5dd2cdbca9ea232a030dfc2e2a1a911776`. All six jobs passed: Rust/PHP/protocol,
Android build/unit contracts, Swift/UIKit (69 tests, zero failures), Android API
26 runtime contracts, Android API 36 runtime contracts and cross-platform
accessibility evidence. The earlier formatting failure below is historical and
does not remain open for this revision.

The downloaded accessibility report from artifact `10345169624` was inspected
and retained as [revision-specific evidence](accessibility-evidence-e963ef5-2026-09-14.json).
It identifies this exact revision and seven successful automated checks in each
of three environments, preserving the source-result byte counts and digests.
This is not manual TalkBack/VoiceOver approval of all UI components. Core
performance/fuzz checks passed in the Rust job; device gesture/frame budgets and
the UI package's full visual release gate remain separate requirements.

No package/tag publication was performed. UI can use this verified Native
candidate while its own later UI changes complete verification.

## Remote platform results

Run https://github.com/push-in/pam-native/actions/runs/34831107281, SHA
`8e8c3524f63fae19c87630547d67942e43019d7e`:

- Swift and UIKit contracts, job 103934418750: success. The downloaded log
  explicitly reports 69 iOS Simulator tests, zero failures and TEST SUCCEEDED.
- Android renderer build and unit contracts, job 103934419065: success.
- Android API 26/36 instrumented jobs 103935521825 and 103935521831: success.
  API 36 also completed the four-test generated-showcase macrobenchmark run.
  Completion does not by itself establish physical-device frame-time budgets.
- Cross-platform accessibility evidence, job 103939984190: success. Its report
  records revision `8e8c352`, seven required checks for each of Android API 26,
  Android API 36 and UIKit. The checker matches actual test results for semantic
  role/state/value, custom actions, reduced motion, font scaling, semantic text
  colors, minimum touch targets and keyboard focus. This is automated native
  contract evidence, not a manual TalkBack/VoiceOver audit of all UI components.
  Artifact: https://github.com/push-in/pam-native/actions/runs/34831107281/artifacts/10343490301
- Rust/PHP contracts failed at formatting, as described below. Local success
  on the corrected SHA does not make that remote job green retroactively.

This supersedes the earlier observation that both platform jobs were running.
It validates Native's executed contract suite, not all UI component interactions,
visual design, accessibility or frame-time budgets. UI has a separate candidate
Verify runs; their results must be tracked separately from this SDK run.

Candidate `2e35530` applies rustfmt to responsive-grid code/tests in layout.rs.
It follows `8e8c352`, whose CI run 34831107281 stopped the Rust/PHP job at the
formatting check before executing Rust tests. Android and Swift/UIKit jobs
were still running at the last observation; this file does not approve them.

Local checks on 2026-09-14, using the existing dependency cache:

- `cargo fmt --all -- --check`: pass.
- `cargo clippy --locked --offline --all-targets -- -D warnings`: pass.
- `cargo test --locked --offline --all-targets`: pass, 39 CLI tests,
  83 engine tests and 12 protocol tests (134 total).

Tests include responsive grid alignment/reflow, intrinsic text measurement,
font scaling, virtualized layout and bounded binary protocol behavior. They
do not establish device visual quality, iOS runtime correctness or release
approval. No package installation or lockfile change was needed. The original
remote run still targets the pre-format commit and must not be described as
having tested this corrected SHA.
