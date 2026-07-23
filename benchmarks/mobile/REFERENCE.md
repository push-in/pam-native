# Physical-device reference

Reference run on 2026-07-23 using a Samsung Galaxy S23 Ultra (`SM-S918B`),
Android 16/API 36, locked benchmark CPU clocks and the minified `benchmark`
build. Raw JSON and Perfetto traces are emitted by `pam mobile benchmark`.

| Scenario | Metric | Result |
| --- | --- | ---: |
| cold startup, 10 runs | time to initial display, median | 346.866 ms |
| cold startup, 10 runs | time to fully drawn, median | 408.258 ms |
| 30 property patches | decode sum, median | 1.361 ms |
| 30 property patches | mount sum, median | 25.053 ms |
| property patches | CPU/frame P50 / P95 | 4.032 / 6.409 ms |
| property patches | frame overrun P50 / P95 | -3.009 / -0.613 ms |
| property patches | anonymous RSS, median | 46,020 KiB |
| 10,000-row list, 6 swipes | CPU/frame P50 / P95 | 4.215 / 14.543 ms |
| 10,000-row list, 6 swipes | frame overrun P50 / P95 | 0.835 / 23.483 ms |

The property-patch medians equal roughly 0.045 ms of binary decode and 0.835 ms
of UI-thread mount per patch. The list P99 overrun was 67.872 ms, so tail
latency remains an explicit optimization target even though median frames are
within budget.

The signed, minified benchmark APK was 5.8 MiB and passed Android's 16 KiB page
alignment check.

These values are a Pam Native baseline, not evidence of a speedup over Nitro
Modules. A valid multiplier requires a React Native/New Architecture report
produced from `contract.json` on the same device, OS, payload and thermal state,
then processed by `compare.py`.

