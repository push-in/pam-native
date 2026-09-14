# Native-owned child visibility synchronization

Status: engine, C ABI and Android integration implemented and tested; UIKit
integration authored locally, pending macOS compilation/tests. Not published.
PAM Native UI's original uncontrolled Treeview reproducer hid descendants
on Android but leaves its 987px engine layout unchanged. Platform-only GONE does
not change the retained declarative tree.

`Engine::set_native_child_visibility(owner, child, visible)` updates Visible
through the existing property-patch path. It accepts only direct children of a
CustomView owner. Unknown nodes, non-custom owners and unrelated nodes are
rejected before mutation. This keeps component expansion policy in UI while
sharing layout synchronization in PAM Native.

The focused Rust regression passed offline with the existing lockfile: hide
reflows a sibling, show restores the original layouts, invalid ownership leaves
layouts unchanged, and a subsequent authored full tree restores authority.
Command: `cargo test -p pam-native-engine --lib native_child_visibility --offline --locked`.

The C header/export now exposes `pam_native_engine_set_native_child_visibility`.
It accepts only 0/1 visibility, validates output and handle pointers, and returns
a standard releasable mutation buffer. The additional ABI regression passed,
including invalid handle/flag/ownership, empty error output, and valid decoded
hide/show batches with released leases. Both targeted tests pass offline.

Remaining implementation: Android runtime/JNI and UIKit runtime; bind a host-scoped child visibility
callback to registered custom views; call it from UI expansion state changes;
verify lifecycle teardown, absent/removed targets, repeated changes, nested
folders and controlled/uncontrolled transitions. Platform callbacks must not
enter an in-progress engine transaction recursively. Do not claim the Treeview
defect fixed until the native-only device geometry roundtrip passes.

Android runtime/JNI wiring is now authored locally (not yet compiled): optional
`NativeChildVisibilityHost` receives a callback from PamRenderer. Requests are
posted to the UI queue, then revalidated against the same owner view and its
direct declarative children. Removed/replaced hosts are ignored. PamRuntime
uses its existing handle lock; JNI uses the engine mutex and publishes standard
mutation batches. Removal and shutdown clear callbacks. UIKit and UI usage are
still absent. Compile/instrument this integration before treating it as delivered.

Android integration has now compiled with the UI consumer and installed on
emulator-5554: engine optimized build 8.74s, Android build 20s, automatic cleanup
96.8 MiB. All 85 engine library tests passed offline and the UI PHP matrix passed.
UI FileTreeFolder invokes the optional callback for internal expansion only;
controlledExpansion remains driven by authored PHP visibility.
The previously failing uncontrolled geometry roundtrip is running with output
`/tmp/pam-tree-native-layout-fixed-20260914`. No device result yet. UIKit bridge
and consumer remain unimplemented, and this combined source is not released.

The uncontrolled Android roundtrip completed successfully: 987px expanded ->
357px collapsed -> 987px restored, descendants returned and the sibling instance
kept its selection. APK SHA-256:
`f2b057e675f814d716aee1496f5c945a0a5bb0c36e6fe5a0cd107ab6bb0c5468`.
The same test previously failed with unchanged 987px height. This proves the
normal-font Android reproduction is fixed, not all variants or UIKit behavior.

UIKit now has the same optional `NativeChildVisibilityHost` capability, queued
renderer validation against the original host and its direct child, lifecycle
callback cleanup, runtime entry point and C bridge to the shared engine. The UI
consumer requests layout only when its content identity or expansion changes;
controlled expansion stays PHP-owned. Both simulator test-runtime shims expose
the new symbol. The UI regression checks hide/show requests, no extra request
on selection, controlled suppression and callback release. These Swift changes
are not yet compiled or executed: this Linux host has no Swift/UIKit toolchain.
Android passing evidence above is retained, not rerun for iOS-only edits.
