# Migrating to Pam Native 1.22.6

## Recovering incomplete prebuilt caches

Update and rebuild the application bundle. On first materialization, the PHP
compiler compares each derived class/template file's size with the existing
pack index. Empty or truncated files are restored atomically from the pack;
only the repaired file's opcode is invalidated. A valid cache is reused
without rewriting, and later renders do not repeat the check or read the pack.

This addresses the zero-byte Feed class observed after an emulator crash while
its source and bundled pack remained intact. The host regression reproduces
class autoload and template rendering with empty/truncated caches, checks
byte-for-byte restoration and reuse of valid files, and verifies that a failed
pack read remains retryable. An incomplete pack still raises an explicit error;
the fix does not reconstruct missing source data or claim recovery of all forms
of corruption. Device validation of the integrated candidate is recorded
separately from this host regression.

## Native module callbacks across reload

Rebuild the native host as well as the application bundle. Native module
completions now resolve through an opaque callback ID tied to their originating
PHP request. Starting a new request invalidates the old mapping, so queued or
late callbacks from shutdown cannot consume a new request's reused PHP ID.
Application code and the module/wire API do not change.

The regression reproduced the failure on Android API 26: two empty timer-cancel
results from shutdown reached the new entry and consumed its Linking IDs. With
the fix, the real bridge test passed on Android APIs 26 and 36: only the new
entry's two Linking results reached PHP, including a typed `canOpenUrl` result.
The HTTPS fixture checks support without opening a URL or accessing the network.
The equivalent iOS bridge change was reviewed in source; it was not executed
on the Linux host. This fix addresses cross-request completion identity and
does not imply that every malformed-payload error has the same cause.

## Typed image callbacks

Update the SDK and rebuild/precompile the application bundle. Existing handlers
typed as `ImageLoadEvent`, `ImageErrorEvent` or `ImageProgressEvent` now receive
the decoded object through the shared native-event decoder. Previously, a
compiled handler could receive a wire string and raise a `TypeError` before
updating its image dimensions or error state.

Both direct handlers and explicit event arguments are supported:

```html
<Image source="file:///photo.jpg" on:load="loaded" />
<Image source="file:///photo.jpg" on:load="loadedFor('photo', $event)" />
```

Use the existing event classes in those handler signatures. No new event class,
constructor parameter or protocol identifier is introduced. Handlers accepting
a string or an untyped parameter still receive their previous payload; handlers
without arguments remain callable. Component relays forwarding an already
decoded event preserve the same object.

The regression dispatches native wire payloads through actual compiled image
bindings with generated and interpreted expressions. It covers load, error and
progress, explicit arguments, legacy signatures and object relays. A separate
application reproduction exercised editor/crop image consumers that failed on
1.22.5 with the same string-to-typed-event error.

## Initial Android text and backgrounds

Rebuild the Android host to use the native changes. The initial property pass
collects rich-text work and applies each requested effect once after the final
property values are available. Ordinary subsequent updates stay immediate.

PamContainer similarly defers initial background assignment until the last
authored color/border override is known. This reuses the existing drawable path;
it does not replace gradients, ripple masks, shadows or clipping. Native
controls, ImageBackground and custom views are outside background batching.
No application flag, layout workaround or prewarming is required.

The accepted application A/B held the PHP bundle byte-identical. In that sample,
the largest mount CPU interval changed from 21.02 to 18.61 ms, Recycler layout
CPU from 44.064 to 39.893 ms, and eleven binds from 29.714 to 27.976 ms. Steady
scroll changed from 0/417 frames classified as jank (p95 10 ms) to 1/420
(p95 11 ms), with GPU median/p95 at 4/5 ms in both. These measurements support
reduced initial main-thread work in that workload, without demonstrating a
material steady-scroll regression.

Cold p95 changed from 133 to 105 ms while the jank count changed from 10/16 to
12/16 frames. Shader work and the small sample constrain that comparison.
This release does not claim sustained 60 FPS or that cold-opening stalls have
been resolved. iOS rendering does not receive the Android batching change;
UIKit execution was not available on the Linux host.

PHP remains `^8.5`; Rust and Android dependency requirements are unchanged.
The protocol/ABI versions are unchanged, and 1.22.5 list/zoom continuity fixes
remain included.
