# Pam Native protocol v1

Pam Native uses one versioned, bounded, little-endian binary protocol between
the persistent PHP runtime, the Rust layout/diff engine and the Android UI
renderer.

## Compatibility

| SDK | Protocol | PHP | Android |
| --- | ---: | --- | --- |
| `pushinbr/pam-native 0.5.x` | `1` | `8.4.x`, `8.5.x` | API 26–36 |

All three peers must support the exact protocol version. A mismatched version is
rejected before any node or mutation is applied. Protocol identifiers are
sequential integers beginning at `1`; existing identifiers are never renamed,
reused or renumbered.

New optional properties, node kinds, event kinds and native operations may only
be appended. A change to an existing field's representation or meaning requires
a new protocol version and an explicit compatibility adapter.

Protocol v1 currently appends properties through ID `405` and events through
ID `34`.

## Frames

Every integer and floating-point value is little-endian.

| Frame | Magic | Producer | Consumer |
| --- | --- | --- | --- |
| complete tree | `PNT1` | PHP | Rust |
| incremental patch | `PNP1` | PHP | Rust |
| UI mutation batch | `PNB1` | Rust | Android |

Each frame starts with its four-byte magic, a `u16` protocol version and bounded
payload counts. Strings and opaque values use a `u32` byte length. Node IDs are
non-zero `u64` values. The implementation rejects duplicate IDs/properties,
cycles, disconnected trees, invalid enum values, trailing bytes and payloads
over their published limits.

Patch application is transactional. A rejected `PNP1` frame leaves the
retained tree untouched; the PHP encoder then resynchronizes with a complete
`PNT1` frame while keeping component and element identities stable. Native
bridges expose the exact failure through `pam_native_engine_last_error`.

The canonical enum tables live in:

- PHP: `NodeKind`, `PropKey`, `EventKind`, `NativeOperation`;
- Rust: `pam-native-protocol/src/lib.rs`;
- Android: `PamProtocol.kt`, `PamRenderer.kt`, `NativeModuleRegistry.kt`.

## Golden compatibility gates

Rust tests pin byte-for-byte v1 tree, patch and batch frames. PHP tests pin
sequential integer enums and deterministic full/patch encoding. Android always
checks `PAM_PROTOCOL_VERSION` before decoding a mutation batch.

Release CI must run all three gates. Changing a golden frame under protocol v1
is a release blocker, not a snapshot update.

## Runtime limits

| Resource | Limit |
| --- | ---: |
| frame | 16 MiB |
| nodes | 100,000 |
| tree depth | 512 |
| properties per node | 128 |
| string/opaque property | 1 MiB |
| queued native event payload | 1 MiB |

These limits are part of protocol v1 and protect both memory use and decoder
complexity.
