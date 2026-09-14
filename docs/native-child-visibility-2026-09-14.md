# Native-owned child visibility synchronization

Status: engine and C ABI implementation; not yet connected to Android/UIKit or
published. PAM Native UI's uncontrolled Treeview reproducer hides descendants
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
