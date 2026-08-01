# iOS performance contract

`PamPerformanceContractTests` exercises the retained virtual-window planner
with 100,000 variable identities and 101 directional velocity samples. The
gate requires completion within two seconds on the shared macOS runner and
allows at most 128 eligible cell roots. It therefore catches both algorithmic
regressions and accidental eager materialization.

CI runs the contract on an iOS Simulator and then compiles the package again
with the Release configuration, whole-module optimization and dead-code
stripping. Physical-device frame pacing remains a release qualification step;
Simulator wall time is only the deterministic lower-level algorithm gate.
