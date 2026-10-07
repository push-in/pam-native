# Mount scenarios

A synthetic app that reproduces the screens where large subtrees appear at
once: a profile header with a 3-column grid of 30 photos, a tab switch that
replaces 20 rows, a full-screen video page swap, and chat-details tabs over
one `VirtualizedList` (header, sticky tab rail, 40 rows with a photo per tab)
switched back and forth either by rebuilding the active tab's rows
(`details`) or with keyed sections (`listSection`/`activeSection`,
`details-keep`).

```bash
python3 generate-photos.py
pam composer install            # or copy a local checkout into vendor/pushinbr/pam-native
pam vendor/bin/pam-native build --benchmark .
adb install -r dist/*-android-benchmark.apk
adb shell cmd package compile -m speed -f dev.pam.mountbench
python3 measure.py emulator-5554 after 4 results    # Perfetto traces + summary.json
python3 measure.py emulator-5554 after 4 results details,details-keep
```

`measure.py` clears the app data before each run, records a Perfetto trace
(frame timeline, gfx/view atrace and the `PamNative.mount`/`PamCommit.*`
sections) per scenario and reports, per mount, the worst and slow app
frames, UI-thread `doFrame` and RenderThread `DrawFrames`, plus
`dumpsys meminfo` after a navigation tour. The details scenarios also report,
per tab tap, the latency from input delivery to the end of its commit and
to the first presented frame after it (`taps`; `return_*` are the switches
back to an already visited tab). Tap coordinates assume a
1080x2400, 420 dpi device.
