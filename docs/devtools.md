# DevTools

Debug builds include an on-device performance overlay. It reports smoothed frame
rate, decode and mount time, rendered node and batch counts, patch versus full
commits, and native heap use.

Start the application and toggle the overlay from another terminal:

```bash
pam mobile dev .
pam mobile devtools .
```

Run `pam mobile devtools .` again to hide it. The receiver and overlay are not
registered in release builds.

The raw Android command is also available for integrations:

```bash
adb shell am broadcast \
  -a dev.pam.nativeapp.action.TOGGLE_DEVTOOLS \
  -p your.application.id.debug
```

Use the overlay alongside `pam mobile benchmark .` and `pam mobile profile .`.
The overlay helps identify an expensive interactive path; the benchmark and
baseline-profile commands provide repeatable evidence suitable for CI.
