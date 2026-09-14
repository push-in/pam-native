# Native UI candidate — Rust validation

## Remote platform results

Run https://github.com/push-in/pam-native/actions/runs/34831107281, SHA
`8e8c3524f63fae19c87630547d67942e43019d7e`:

- Swift and UIKit contracts, job 103934418750: success. The downloaded log
  explicitly reports 69 iOS Simulator tests, zero failures and TEST SUCCEEDED.
- Android renderer build and unit contracts, job 103934419065: success.
- Android API 26/36 instrumented jobs were still in progress when recorded.
- Rust/PHP contracts failed at formatting, as described below. Local success
  on the corrected SHA does not make that remote job green retroactively.

This supersedes the earlier observation that both platform jobs were running.
It validates Native's executed contract suite, not all UI component interactions,
visual design, accessibility or frame-time budgets. UI has a separate candidate
Verify run 34831423557 that was still running at this checkpoint.

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
