# Pam Native protocol v1

Pam Native uses one bounded, little-endian binary protocol between persistent
PHP, the Rust layout/diff engine and the Android/iOS renderers.

| SDK | Protocol | PHP | Android | iOS |
| --- | ---: | --- | --- | --- |
| `pushinbr/pam-native 0.6.x` | `1` | `8.4.x`, `8.5.x` | API 26–36 | current supported Xcode/iOS toolchain |

All peers must support the exact protocol version. Existing sequential integer
identifiers are never renamed, reused or renumbered. Optional node kinds,
properties, events and operations may only be appended; changing an existing
field requires a new protocol version.

## Frames

| Frame | Magic | Producer | Consumer |
| --- | --- | --- | --- |
| complete tree | `PNT1` | PHP | Rust |
| incremental patch | `PNP1` | PHP | Rust |
| UI mutation batch | `PNB1` | Rust | Android / iOS |

Each frame starts with its four-byte magic and a `u16` version. Counts and byte
lengths are bounded. Node IDs are non-zero `u64` values. Decoders reject
duplicates, cycles, disconnected trees, invalid enum values, trailing bytes and
oversized payloads before applying mutations. Text properties, packed
list/section entries and module-map strings must be valid UTF-8; malformed input
is rejected rather than normalized. Opaque tag-`5` properties remain binary.
Floating-point properties and module-map decimals reject `NaN` and infinity at
both encode and decode boundaries.
Module maps canonically encode their portable ASCII keys in ascending byte
order. PHP, Kotlin and Swift share a golden fixture, while decoders continue to
accept valid pre-existing maps in any key order.

The canonical enums are `NodeKind`, `PropKey`, `EventKind` and
`NativeOperation` in PHP, the `pam-native-protocol` Rust crate, and
`PamProtocol.kt` plus the Android registries. Protocol v1 currently appends
properties through ID `451` and events through ID `65`. IDs `450` and `451`
describe custom accessibility actions and their handler; event `65` returns
the selected stable action name.

## Compatibility gates

Rust tests pin exact v1 tree, patch and batch bytes. PHP tests parse the PHP,
Rust and Kotlin property enums and the PHP/Kotlin event enums, requiring
identical names, values and append-only order in addition to deterministic
full/patch encoding. The repository parity gate also compares protocol version,
frame/mutation/property/value ceilings across PHP, Rust, Kotlin and Swift.
Android and iOS check the protocol version before decoding. Changing a golden
frame while retaining protocol v1 is a release blocker.

## Limits

| Resource | Limit |
| --- | ---: |
| frame | 16 MiB |
| nodes | 100,000 |
| tree depth | 512 |
| properties per node | 128 |
| string/opaque property | 1 MiB |
| queued event payload | 1 MiB |

Rust, Android and iOS boundary tests accept the exact property/value ceilings,
reject 129 properties or 1 MiB plus one byte, and check length before copying a
declared payload.
