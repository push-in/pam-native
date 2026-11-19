#!/usr/bin/env bash
# usage: scripts/ci-ime-ready.sh
# Before the instrumented contracts: the soft keyboard also shows next to the
# emulator's hardware keyboard, and the default IME is installed, enabled and
# bound once (a cold Gboard on a fresh CI emulator dropped the first
# show requests of whichever keyboard test ran first).
#
# An "Application Not Responding: com.android.systemui" dialog (a loaded
# runner starves System UI) takes window focus from every test activity for
# the rest of the run: injected touches go to the dialog and ~50 focus and
# IME contracts fail at once. With error dialogs hidden, Android restarts
# the unresponsive process instead of asking (Settings.Global
# HIDE_ERROR_DIALOGS, API 28+; older images ignore it).
set -uo pipefail
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb shell settings put secure show_ime_with_hard_keyboard 1
for _ in $(seq 1 60); do
  ime=$(adb shell settings get secure default_input_method 2>/dev/null | tr -d '\r')
  if [[ -n ${ime} && ${ime} != null ]] && adb shell ime list -s 2>/dev/null | tr -d '\r' | grep -qx "${ime}"; then
    adb shell ime enable "${ime}" >/dev/null 2>&1 || true
    adb shell ime set "${ime}" >/dev/null 2>&1 || true
    echo "IME ready: ${ime}"
    # Start its service once so the first test does not pay the cold start.
    adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
    exit 0
  fi
  sleep 2
done
echo "No default IME after 120 s; keyboard contracts may fail" >&2
adb shell ime list -s >&2 || true
exit 0
