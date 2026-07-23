# Pam Native protocol v1

Pam Native uses one bounded, little-endian binary protocol between persistent
PHP, the Rust layout/diff engine and the Android renderer.

| SDK | Protocol | PHP | Android |
| --- | ---: | --- | --- |
| `pam/native 0.1.x` | `1` | `8.4.x` | API 26–36 |

All peers must support the exact protocol version. Existing sequential integer
identifiers are never renamed, reused or renumbered. Optional node kinds,
properties, events and operations may only be appended; changing an existing
field requires a new protocol version.

## Frames

| Frame | Magic | Producer | Consumer |
| --- | --- | --- | --- |
| complete tree | `PNT1` | PHP | Rust |
| incremental patch | `PNP1` | PHP | Rust |
| UI mutation batch | `PNB1` | Rust | Android |

Each frame starts with its four-byte magic and a `u16` version. Counts and byte
lengths are bounded. Node IDs are non-zero `u64` values. Decoders reject
duplicates, cycles, disconnected trees, invalid enum values, trailing bytes and
oversized payloads before applying mutations.

The canonical enums are `NodeKind`, `PropKey`, `EventKind` and
`NativeOperation` in PHP, the `pam-native-protocol` Rust crate, and
`PamProtocol.kt` plus the Android registries.

## Compatibility gates

Rust tests pin exact v1 tree, patch and batch bytes. PHP tests pin sequential
integer enums and deterministic full/patch encoding. Android checks the
protocol version before decoding. Changing a golden frame while retaining
protocol v1 is a release blocker.

## Limits

| Resource | Limit |
| --- | ---: |
| frame | 16 MiB |
| nodes | 100,000 |
| tree depth | 512 |
| properties per node | 128 |
| string/opaque property | 1 MiB |
| queued event payload | 1 MiB |

