# Activity recreation and retained event streams

Android may recreate PamActivity after applying resource overlays even when its
configuration changes are otherwise handled. The PHP runtime survives this
surface replacement. Previously NativeModuleRegistry.retire() closed the global
push, notification-action, deep-link and incoming-share waiters. Their failures
reached the still-live PHP subscriptions, which removed themselves and emitted
"module closed" errors. The subsequent surface therefore lost these event streams.

SurfaceRetirableModule separates surface cleanup from final runtime shutdown.
Notifications retire their old Activity handler/executor, while global waiters
survive. Linking and incoming-share modules have no surface resources to release.
A final registry close still closes these streams; reload still invalidates them.
The completion-generation guard captures only its AtomicLong, avoiding retention
of the old registry and its Activity through a pending process event callback.

The replacement explicitly owns the context-free HTTP, Storage, SQLite, Cache
and Timers modules. Their queued/in-flight work belongs to the retained PHP
runtime, not the discarded Activity. Storage, SQLite and Cache use application
context; HTTP owns only file roots; Timers owns its scheduler. Old registries
neither retire nor close transferred instances. Final close of the new owner does.
Activity, permission, media-player and sensor modules still retire normally.

The first populated-app recreation exposed a second concrete failure: shutting
down the old Storage executor interrupted an in-flight SharedPreferences commit.
Preserving durable-operation ownership fixes that failure without cancelling a
PHP promise or duplicating a write. Pending timers likewise retain their callback
and cancellation ID through replacement.

Validation on 2026-10-13:

- All 277 Android JVM tests pass; main and instrumented Kotlin compile.
- Four focused Android API 36 instrumented regressions pass (2.216 seconds):
  retained push/action/link/share waiters and final close; pending timer completion
  and cancellation; queued Storage/SQLite/Cache operations; an in-flight HTTP
  request against a bounded local loopback server.
- A populated ZeChat Firebase QA candidate passes Activity.recreate() on API 36
  (one test, 2.655 seconds). The test checks that the replacement Activity renders
  the expected visible “Conversas” node, has a mounted tree, and has no debug error
  overlay. Account/session data was preserved. This candidate included the event,
  timer, Storage and SQLite fixes; subsequent HTTP/Cache ownership is covered by
  the four-module native regression above.

Observed original QA log: PID 1994, 17:49:23 dynamic resource-overlay update,
second Activity creation at 17:49:24.391, three close errors at 17:49:24.422–425.
Only one embedded-PHP initialization occurred. This supports recreation/retirement
as the listener failure cause, not an out-of-memory restart. The original blank
surface's exact triggering callback cannot be recovered from the log; the actual
recreation test now verifies rendered content and the absence of the observed
error overlay instead of inferring success from source inspection.

A rotation may be intercepted by configChanges, so the regression deliberately
uses Activity.recreate(). Device instrumentation uses only dedicated debug QA
packages; it does not clear application data. iOS is unchanged by this Android
lifecycle correction; no Xcode execution was performed on the Linux host.
