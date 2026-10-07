# Mount scenarios

A synthetic app that reproduces the screens where large subtrees appear at
once: a profile header with a 3-column grid of 30 photos, a tab switch that
replaces 20 rows, and a full-screen video page swap.

```bash
python3 generate-photos.py
pam composer install            # or copy a local checkout into vendor/pushinbr/pam-native
pam vendor/bin/pam-native build --benchmark .
adb install -r dist/*-android-benchmark.apk
adb shell cmd package compile -m speed -f dev.pam.mountbench
python3 measure.py emulator-5554 after 4 results    # Perfetto traces + summary.json
```

`measure.py` clears the app data before each run, records a Perfetto trace
(frame timeline, gfx/view atrace and the `PamNative.mount`/`PamCommit.*`
sections) per scenario and reports, per mount, the worst and slow app
frames, UI-thread `doFrame` and RenderThread `DrawFrames`, plus
`dumpsys meminfo` after a navigation tour. Tap coordinates assume a
1080x2400, 420 dpi device.
